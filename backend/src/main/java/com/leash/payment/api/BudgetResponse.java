package com.leash.payment.api;

import com.leash.payment.domain.BudgetSnapshot;

/** Live weekly budget for the authenticated agent. */
public record BudgetResponse(
    long limitMinor, long spentMinor, long reservedMinor, long remainingMinor) {

  static BudgetResponse from(BudgetSnapshot budget) {
    return new BudgetResponse(
        budget.limitMinor(), budget.spentMinor(), budget.reservedMinor(), budget.remainingMinor());
  }
}
