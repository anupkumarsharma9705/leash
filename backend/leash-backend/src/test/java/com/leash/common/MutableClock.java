package com.leash.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** A clock tests can move forward to cross reconciler thresholds. */
public final class MutableClock extends Clock {

  private volatile Instant instant;
  private final ZoneId zone;

  public MutableClock(Instant instant) {
    this(instant, ZoneOffset.UTC);
  }

  public MutableClock(Instant instant, ZoneId zone) {
    this.instant = Objects.requireNonNull(instant, "instant is required");
    this.zone = Objects.requireNonNull(zone, "zone is required");
  }

  public void setInstant(Instant instant) {
    this.instant = Objects.requireNonNull(instant, "instant is required");
  }

  public synchronized void advance(Duration duration) {
    Objects.requireNonNull(duration, "duration is required");
    this.instant = this.instant.plus(duration);
  }

  @Override
  public Instant instant() {
    return instant;
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MutableClock(instant, zone);
  }
}
