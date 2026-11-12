package com.leash.agent.api;

import com.leash.agent.app.AgentService;
import com.leash.agent.domain.AgentSuspendedException;
import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.agent.domain.InvalidApiKeyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates agent requests with {@code Authorization: Bearer lsh_...}.
 *
 * <p>This filter runs only for the agent API. Admin and other routes are never authenticated by an
 * agent key. Authentication failures are RFC 7807 responses and never echo the presented key.
 */
public final class AgentApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AgentApiKeyAuthenticationFilter.class);
  private static final String BEARER = "Bearer ";

  private final AgentService agentService;
  private final ObjectMapper objectMapper;

  public AgentApiKeyAuthenticationFilter(AgentService agentService, ObjectMapper objectMapper) {
    this.agentService = agentService;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // getServletPath() can be empty depending on the servlet mapping, so derive the path from the
    // request URI and strip any context path.
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.equals("/api/v1/budget")
        || path.equals("/api/v1/payments")
        || path.startsWith("/api/v1/payments/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || header.isBlank()) {
      reject(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing agent API key");
      return;
    }
    if (!header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      reject(
          response,
          request,
          HttpStatus.UNAUTHORIZED,
          "INVALID_API_KEY",
          "Authorization header must use the Bearer scheme");
      return;
    }

    String presentedKey = header.substring(BEARER.length()).trim();
    try {
      AuthenticatedAgent agent = agentService.authenticate(presentedKey);
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(
              agent, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
      authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authentication);
      filterChain.doFilter(request, response);
    } catch (AgentSuspendedException exception) {
      log.warn("Rejected agent request: agentId={} is suspended", exception.agentId());
      reject(response, request, HttpStatus.FORBIDDEN, "AGENT_SUSPENDED", "Agent is suspended");
    } catch (InvalidApiKeyException exception) {
      reject(response, request, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "Invalid API key");
    }
  }

  private void reject(
      HttpServletResponse response,
      HttpServletRequest request,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    if (status == HttpStatus.UNAUTHORIZED) {
      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

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
