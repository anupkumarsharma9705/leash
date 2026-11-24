package com.leash.policy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/** A half-open UTC budget window: {@code [startInclusive, endExclusive)}. */
public record BudgetPeriod(Instant startInclusive, Instant endExclusive) {

  public BudgetPeriod {
    Objects.requireNonNull(startInclusive, "startInclusive is required");
    Objects.requireNonNull(endExclusive, "endExclusive is required");
    if (!endExclusive.isAfter(startInclusive)) {
      throw new IllegalArgumentException("endExclusive must be after startInclusive");
    }
  }

  /** Returns the Monday-to-Monday UTC week that contains the given instant. */
  public static BudgetPeriod weekContaining(Instant instant) {
    Objects.requireNonNull(instant, "instant is required");
    LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    return new BudgetPeriod(
        monday.atStartOfDay(ZoneOffset.UTC).toInstant(),
        monday.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant());
  }

  public static BudgetPeriod current(Clock clock) {
    Objects.requireNonNull(clock, "clock is required");
    return weekContaining(clock.instant());
  }

  public boolean contains(Instant instant) {
    Objects.requireNonNull(instant, "instant is required");
    return !instant.isBefore(startInclusive) && instant.isBefore(endExclusive);
  }
}
