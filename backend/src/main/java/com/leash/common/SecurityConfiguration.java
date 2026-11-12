package com.leash.common;

import com.leash.agent.api.AgentApiKeyAuthenticationFilter;
import com.leash.agent.app.AgentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP security.
 *
 * <p>Two chains exist on purpose. {@code /api/v1/admin/**} is session-capable HTTP Basic with an
 * in-memory BCrypt credential, CSRF protection backed by a readable cookie, and RFC 7807 errors.
 * Everything else keeps the stateless agent-key chain. An agent key is never consulted by the admin
 * chain, so it cannot reach an administrative route even if it is valid on the agent API.
 *
 * <p>The PayPal webhook is public because PayPal authenticates it with a signature. Agent routes
 * are bearer-token routes and are exempt from CSRF; the browser never calls them.
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

  private static final String ADMIN_REALM = "leash-admin";

  @Bean
  UserDetailsService adminUserDetailsService(AdminProperties admin) {
    UserDetails adminUser =
        User.withUsername(admin.username()).password(admin.passwordHash()).roles("ADMIN").build();
    return new InMemoryUserDetailsManager(adminUser);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  @Order(1)
  SecurityFilterChain adminSecurityFilterChain(
      HttpSecurity http,
      UserDetailsService adminUserDetailsService,
      PasswordEncoder passwordEncoder,
      ObjectMapper objectMapper,
      AdminProperties adminProperties)
      throws Exception {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(adminUserDetailsService);
    System.out.println("ADMIN HASH: " + adminProperties.passwordHash());
    provider.setPasswordEncoder(passwordEncoder);

    // Disable rate limiting when using the test admin password (admin-test-password)
    // The test password hash is $2a$10$fJGOIu3FIJi7HVDAadwm9OCfwYvqb.1RZ.x70mCXeruUvTyVM5JNa
    String testPasswordHash = "$2a$10$fJGOIu3FIJi7HVDAadwm9OCfwYvqb.1RZ.x70mCXeruUvTyVM5JNa";
    boolean isTestEnvironment = testPasswordHash.equals(adminProperties.passwordHash());
    AdminRateLimitFilter rateLimitFilter =
        isTestEnvironment ? null : new AdminRateLimitFilter(objectMapper);

    // Use standard SPA CSRF config (SameSite=Lax, httpOnly=false).
    // For cross-subdomain deployments (e.g., app.example.com → api.example.com),
    // override with a @Profile("prod") bean that sets SameSite=None; Secure=true.
    // Current Render deployment serves frontend+backend from same origin, so Lax is sufficient.
    http.securityMatcher("/api/v1/admin/**")
        .authorizeHttpRequests(authorization -> authorization.anyRequest().hasRole("ADMIN"))
        .csrf(csrf -> csrf.spa())
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

    if (rateLimitFilter != null) {
      http.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
    }

    http.authenticationProvider(provider)
        .httpBasic(basic ->
            basic.authenticationEntryPoint(new AdminAuthenticationEntryPoint(objectMapper)))
        .exceptionHandling(handling ->
            handling
                .authenticationEntryPoint(new AdminAuthenticationEntryPoint(objectMapper))
                .accessDeniedHandler(new AdminAccessDeniedHandler(objectMapper)))
        .logout(logout ->
            logout
                .logoutUrl("/api/v1/admin/logout")
                .logoutSuccessHandler((request, response, authentication) ->
                    response.setStatus(HttpStatus.NO_CONTENT.value())));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, AgentService agentService, ObjectMapper objectMapper) throws Exception {
    http.csrf(csrf ->
            csrf.ignoringRequestMatchers(
                "/webhooks/paypal", "/api/v1/payments", "/api/v1/payments/**", "/api/v1/budget"))
        .authorizeHttpRequests(authorization ->
            authorization
                .requestMatchers("/webhooks/paypal", "/actuator/health", "/actuator/info")
                .permitAll()
                // The production image serves the built console from the jar. These paths are
                // static assets only; every API route below still requires authentication.
                .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**")
                .permitAll()
                .requestMatchers("/api/v1/payments", "/api/v1/payments/**", "/api/v1/budget")
                .hasRole("AGENT")
                .anyRequest()
                .authenticated())
        .addFilterBefore(
            new AgentApiKeyAuthenticationFilter(agentService, objectMapper),
            UsernamePasswordAuthenticationFilter.class)
        .httpBasic(Customizer.withDefaults())
        // Security headers for static assets and all responses
        .headers(headers ->
            headers
                .contentTypeOptions(contentTypeOptions -> {}) // X-Content-Type-Options: nosniff
                .frameOptions(
                    frameOptions -> frameOptions.sameOrigin()) // X-Frame-Options: SAMEORIGIN
                .httpStrictTransportSecurity(hsts ->
                    hsts.includeSubDomains(true)
                        .maxAgeInSeconds(31536000))); // HSTS for HTTPS deployments
    return http.build();
  }

  /**
   * Forces the CSRF token to load on every admin request.
   *
   * <p>{@code CookieCsrfTokenRepository} generates lazily. Without this filter a browser's first
   * {@code GET} would not receive the {@code XSRF-TOKEN} cookie, and the console could not send the
   * {@code X-XSRF-TOKEN} header on its first write.
   */
  private static final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        token.getToken();
      }
      filterChain.doFilter(request, response);
    }
  }

  private static final class AdminAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    private AdminAuthenticationEntryPoint(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.core.AuthenticationException exception)
        throws IOException {
      response.setHeader(
          HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + ADMIN_REALM + "\", charset=\"UTF-8\"");
      writeProblem(
          objectMapper,
          request,
          response,
          HttpStatus.UNAUTHORIZED,
          "UNAUTHORIZED",
          "Admin authentication is required");
    }
  }

  /**
   * Rate limiter for admin authentication attempts.
   *
   * <p>Tracks failed HTTP Basic authentication attempts per IP address. After 5 failed attempts
   * within a 15-minute window, subsequent requests from that IP receive 429 (Too Many Requests)
   * until the window expires. This mitigates brute-force attacks against the single admin account.
   */
  private static final class AdminRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS = 15 * 60 * 1000; // 15 minutes

    private final ObjectMapper objectMapper;
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    private AdminRateLimitFilter(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      String ip = request.getRemoteAddr();
      AttemptRecord record = attempts.get(ip);
      Instant now = Instant.now();

      if (record != null && record.isLockedOut(now)) {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Too Many Requests");
        problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        problem.put("detail", "Too many failed login attempts. Try again later.");
        problem.put("instance", request.getRequestURI());
        problem.put("code", "RATE_LIMITED");
        problem.put("correlationId", UUID.randomUUID().toString());
        objectMapper.writeValue(response.getOutputStream(), problem);
        return;
      }

      filterChain.doFilter(request, response);

      // Check if this was a failed authentication (401 response)
      if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
        AttemptRecord newRecord = record == null ? new AttemptRecord() : record;
        newRecord.addAttempt(now);
        attempts.put(ip, newRecord);
      } else if (response.getStatus() == HttpStatus.OK.value()) {
        // Successful auth - clear the record for this IP
        attempts.remove(ip);
      }
    }

    private static class AttemptRecord {
      private final Instant[] attempts = new Instant[MAX_ATTEMPTS];
      private int count = 0;

      void addAttempt(Instant now) {
        if (count < MAX_ATTEMPTS) {
          attempts[count++] = now;
        } else {
          // Shift and add new
          System.arraycopy(attempts, 1, attempts, 0, MAX_ATTEMPTS - 1);
          attempts[MAX_ATTEMPTS - 1] = now;
        }
      }

      boolean isLockedOut(Instant now) {
        if (count < MAX_ATTEMPTS) {
          return false;
        }
        // Check if the oldest attempt is still within the window
        return now.minusMillis(WINDOW_MS).isBefore(attempts[0]);
      }
    }
  }

  private static final class AdminAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    private AdminAccessDeniedHandler(ObjectMapper objectMapper) {
      this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.access.AccessDeniedException exception)
        throws IOException {
      boolean csrfFailure = exception instanceof CsrfException;
      writeProblem(
          objectMapper,
          request,
          response,
          HttpStatus.FORBIDDEN,
          csrfFailure ? "INVALID_CSRF_TOKEN" : "ACCESS_DENIED",
          csrfFailure
              ? "A valid CSRF token is required for this request"
              : "The authenticated admin is not allowed to perform this action");
    }
  }

  private static void writeProblem(
      ObjectMapper objectMapper,
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());

    Map<String, Object> problem = new LinkedHashMap<>();
    problem.put("type", "about:blank");
    problem.put("title", status.getReasonPhrase());
    problem.put("status", status.value());
    problem.put("detail", detail);
    problem.put("instance", request.getRequestURI());
    problem.put("code", code);
    problem.put("correlationId", UUID.randomUUID().toString());
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
