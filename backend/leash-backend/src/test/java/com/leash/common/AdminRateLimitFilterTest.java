package com.leash.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class AdminRateLimitFilterTest {

  private static final String IP = "203.0.113.9";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-25T12:00:00Z"));
  private final AdminRateLimitFilter filter = new AdminRateLimitFilter(new ObjectMapper(), clock);

  @Test
  void locksOutAfterFiveFailedAttemptsAndRecoversAfterTheWindow() throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(attempt(401).getStatus()).isEqualTo(401);
    }

    MockHttpServletResponse limited = attempt(401);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getContentAsString()).contains("RATE_LIMITED");

    clock.advanceSeconds(16 * 60);
    assertThat(attempt(401).getStatus()).isEqualTo(401);
  }

  @Test
  void successfulAuthenticationClearsTheCounter() throws Exception {
    for (int attempt = 0; attempt < 4; attempt++) {
      assertThat(attempt(401).getStatus()).isEqualTo(401);
    }
    assertThat(attempt(200).getStatus()).isEqualTo(200);

    for (int attempt = 0; attempt < 5; attempt++) {
      assertThat(attempt(401).getStatus()).isEqualTo(401);
    }
    assertThat(attempt(401).getStatus()).isEqualTo(429);
  }

  private MockHttpServletResponse attempt(int status) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/agents");
    request.setRemoteAddr(IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(status);
    filter.doFilter(request, response, chain);
    return response;
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advanceSeconds(long seconds) {
      now = now.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
