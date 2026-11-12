package com.leash.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of time for business code.
 *
 * <p>Business code must never call {@code Instant.now()} directly; injecting {@link Clock} keeps
 * budget-period boundaries testable.
 */
@Configuration
class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
