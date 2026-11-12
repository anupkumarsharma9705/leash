package com.leash.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BudgetPeriodTest {

  @Test
  void weekStartsOnMondayAtMidnightUtc() {
    BudgetPeriod period = BudgetPeriod.weekContaining(Instant.parse("2026-11-11T10:15:30Z"));

    assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
    assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
  }

  @Test
  void exactlyMondayMidnightStartsTheNewWeek() {
    BudgetPeriod period = BudgetPeriod.weekContaining(Instant.parse("2026-11-09T00:00:00Z"));

    assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
    assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
  }

  @Test
  void lastInstantOfSundayStaysInTheCurrentWeek() {
    BudgetPeriod period = BudgetPeriod.weekContaining(Instant.parse("2026-11-15T23:59:59.999Z"));

    assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
    assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
  }

  @Test
  void weekCanStartInThePreviousYear() {
    // 2026-01-01 is a Thursday, so its week starts on Monday 2025-12-29.
    BudgetPeriod period = BudgetPeriod.weekContaining(Instant.parse("2026-01-01T12:00:00Z"));

    assertThat(period.startInclusive()).isEqualTo(Instant.parse("2025-12-29T00:00:00Z"));
    assertThat(period.endExclusive()).isEqualTo(Instant.parse("2026-01-05T00:00:00Z"));
  }

  @Test
  void weekCanEndInTheNextYear() {
    // 2027-01-01 is a Friday, so its week ends on Monday 2027-01-04.
    BudgetPeriod period = BudgetPeriod.weekContaining(Instant.parse("2027-01-01T12:00:00Z"));

    assertThat(period.startInclusive()).isEqualTo(Instant.parse("2026-12-28T00:00:00Z"));
    assertThat(period.endExclusive()).isEqualTo(Instant.parse("2027-01-04T00:00:00Z"));
  }

  @Test
  void periodContainsItsStartAndExcludesItsEnd() {
    BudgetPeriod period =
        new BudgetPeriod(
            Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z"));

    assertThat(period.contains(Instant.parse("2026-11-09T00:00:00Z"))).isTrue();
    assertThat(period.contains(Instant.parse("2026-11-15T23:59:59.999Z"))).isTrue();
    assertThat(period.contains(Instant.parse("2026-11-16T00:00:00Z"))).isFalse();
    assertThat(period.contains(Instant.parse("2026-11-08T23:59:59.999Z"))).isFalse();
  }

  @Test
  void currentDerivesTheWeekFromTheClock() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-11T10:15:30Z"), ZoneOffset.UTC);

    assertThat(BudgetPeriod.current(clock))
        .isEqualTo(BudgetPeriod.weekContaining(Instant.parse("2026-11-11T10:15:30Z")));
  }
}
