package com.leash.policy;

import java.util.List;
import java.util.Objects;

/**
 * The result of evaluating one payment request against an agent policy.
 *
 * <p>A {@code DENY} always carries at least one reason; the first reason is the primary one. A
 * non-deny outcome never carries reasons.
 */
public record PolicyDecision(
    PolicyOutcome outcome, List<PolicyReason> reasons, BudgetPeriod budgetPeriod) {

  public PolicyDecision {
    Objects.requireNonNull(outcome, "outcome is required");
    Objects.requireNonNull(reasons, "reasons is required");
    Objects.requireNonNull(budgetPeriod, "budgetPeriod is required");
    reasons = List.copyOf(reasons);
    if (outcome == PolicyOutcome.DENY && reasons.isEmpty()) {
      throw new IllegalArgumentException("DENY requires at least one reason");
    }
    if (outcome != PolicyOutcome.DENY && !reasons.isEmpty()) {
      throw new IllegalArgumentException("Only DENY may carry reasons");
    }
  }
}
