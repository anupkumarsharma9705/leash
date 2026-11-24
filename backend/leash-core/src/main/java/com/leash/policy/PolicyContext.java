package com.leash.policy;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * All inputs needed to evaluate a payment request.
 *
 * <p>Budget aggregates ({@code spentMinor}, {@code reservedMinor}) must already be scoped to {@link
 * #budgetPeriod()} by the caller. Pending approvals count as reserved budget.
 */
public record PolicyContext(
    UUID agentId,
    AgentStatus agentStatus,
    AgentPolicy policy,
    long amountMinor,
    String currency,
    String payeeEmail,
    long spentMinor,
    long reservedMinor,
    Clock clock) {

  public PolicyContext {
    Objects.requireNonNull(agentId, "agentId is required");
    Objects.requireNonNull(agentStatus, "agentStatus is required");
    Objects.requireNonNull(policy, "policy is required");
    Objects.requireNonNull(clock, "clock is required");
    if (spentMinor < 0) {
      throw new IllegalArgumentException("spentMinor must not be negative");
    }
    if (reservedMinor < 0) {
      throw new IllegalArgumentException("reservedMinor must not be negative");
    }
  }

  /** The UTC week this evaluation applies to. */
  public BudgetPeriod budgetPeriod() {
    return BudgetPeriod.current(clock);
  }
}
