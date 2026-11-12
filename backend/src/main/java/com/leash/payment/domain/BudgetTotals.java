package com.leash.payment.domain;

/** Budget aggregates for one agent and one budget period, computed inside the agent lock. */
public record BudgetTotals(long spentMinor, long reservedMinor) {

  public BudgetTotals {
    if (spentMinor < 0) {
      throw new IllegalArgumentException("spentMinor must not be negative");
    }
    if (reservedMinor < 0) {
      throw new IllegalArgumentException("reservedMinor must not be negative");
    }
  }

  public static BudgetTotals empty() {
    return new BudgetTotals(0, 0);
  }

  /** Adds an amount without wrapping; extreme inputs saturate at {@link Long#MAX_VALUE}. */
  public BudgetTotals plusSpent(long amountMinor) {
    return new BudgetTotals(saturatingAdd(spentMinor, amountMinor), reservedMinor);
  }

  /** Adds an amount without wrapping; extreme inputs saturate at {@link Long#MAX_VALUE}. */
  public BudgetTotals plusReserved(long amountMinor) {
    return new BudgetTotals(spentMinor, saturatingAdd(reservedMinor, amountMinor));
  }

  /**
   * Removes one reservation, floored at zero.
   *
   * <p>Used when an approval re-check releases the payment's own reservation before evaluating the
   * hard rules again; the row is still {@code PENDING_APPROVAL} at that moment.
   */
  public BudgetTotals minusReserved(long amountMinor) {
    if (amountMinor < 0) {
      throw new IllegalArgumentException("amountMinor must not be negative");
    }
    long released = reservedMinor - amountMinor;
    return new BudgetTotals(spentMinor, Math.max(0, released));
  }

  private static long saturatingAdd(long left, long right) {
    long sum = left + right;
    return sum < 0 ? Long.MAX_VALUE : sum;
  }
}
