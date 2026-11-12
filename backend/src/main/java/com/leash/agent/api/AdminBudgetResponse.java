package com.leash.agent.api;

/** Current-week budget numbers, with the limit taken from the current policy. */
public record AdminBudgetResponse(
    long limitMinor, long spentMinor, long reservedMinor, long remainingMinor) {

  static AdminBudgetResponse of(long limitMinor, long spentMinor, long reservedMinor) {
    long committed = spentMinor + reservedMinor;
    long remainingMinor = committed < 0 || committed >= limitMinor ? 0 : limitMinor - committed;
    return new AdminBudgetResponse(limitMinor, spentMinor, reservedMinor, remainingMinor);
  }
}
