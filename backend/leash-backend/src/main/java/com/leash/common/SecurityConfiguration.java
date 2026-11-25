package com.leash.common;

import com.leash.agent.api.AgentApiKeyAuthenticationFilter;
import com.leash.agent.app.AgentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
      AdminRateLimitProperties rateLimitProperties,
      Clock clock)
      throws Exception {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(adminUserDetailsService);
    provider.setPasswordEncoder(passwordEncoder);

    // Use standard SPA CSRF config (SameSite=Lax, httpOnly=false).
    // For cross-subdomain deployments (e.g., app.example.com → api.example.com),
    // override with a @Profile("prod") bean that sets SameSite=None; Secure=true.
    // Current Render deployment serves frontend+backend from same origin, so Lax is sufficient.
    http.securityMatcher("/api/v1/admin/**")
        .authorizeHttpRequests(authorization -> authorization.anyRequest().hasRole("ADMIN"))
        .csrf(csrf -> csrf.spa())
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

    if (rateLimitProperties.enabled()) {
      http.addFilterBefore(
          new AdminRateLimitFilter(objectMapper, clock),
          UsernamePasswordAuthenticationFilter.class);
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
                .requestMatchers(
                    "/webhooks/paypal",
                    "/actuator/health",
                    "/actuator/health/readiness",
                    "/actuator/info")
                .permitAll()
                // Prometheus scrapes are unauthenticated on the demo deployment; restrict this
                // route at the proxy if metrics must stay private.
                .requestMatchers(
                    "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
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
