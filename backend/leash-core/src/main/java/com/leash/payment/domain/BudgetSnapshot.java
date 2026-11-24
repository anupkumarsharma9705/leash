package com.leash.payment.domain;

/** The budget numbers returned with a payment decision, captured at decision time. */
public record BudgetSnapshot(long limitMinor, long spentMinor, long reservedMinor) {

  public BudgetSnapshot {
    if (limitMinor < 0) {
      throw new IllegalArgumentException("limitMinor must not be negative");
    }
    if (spentMinor < 0) {
      throw new IllegalArgumentException("spentMinor must not be negative");
    }
    if (reservedMinor < 0) {
      throw new IllegalArgumentException("reservedMinor must not be negative");
    }
  }

  /** Remaining budget, saturated at zero. */
  public long remainingMinor() {
    long committed = spentMinor + reservedMinor;
    if (committed < 0) {
      return 0;
    }
    return committed >= limitMinor ? 0 : limitMinor - committed;
  }
}
