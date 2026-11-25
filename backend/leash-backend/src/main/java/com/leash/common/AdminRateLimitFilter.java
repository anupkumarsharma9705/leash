package com.leash.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Rate limiter for admin authentication attempts.
 *
 * <p>Tracks failed HTTP Basic authentication attempts per IP address. After 5 failed attempts
 * within a 15-minute window, subsequent requests from that IP receive 429 (Too Many Requests) until
 * the window expires. This mitigates brute-force attacks against the single admin account.
 *
 * <p>This filter is always installed in production. It is controlled by {@code
 * leash.admin.rate-limit.enabled}, never by comparing the configured password hash.
 */
final class AdminRateLimitFilter extends OncePerRequestFilter {

  private static final int MAX_ATTEMPTS = 5;
  private static final long WINDOW_MS = 15 * 60 * 1000; // 15 minutes

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

  AdminRateLimitFilter(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String ip = request.getRemoteAddr();
    AttemptRecord record = attempts.get(ip);
    Instant now = clock.instant();

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

  private static final class AttemptRecord {
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
