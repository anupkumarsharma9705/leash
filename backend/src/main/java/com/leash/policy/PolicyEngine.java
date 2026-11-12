package com.leash.policy;

import com.leash.common.money.SupportedCurrency;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic spending policy evaluation.
 *
 * <p>This class is pure: it performs no I/O, uses no Spring or JPA types, and never reads the
 * current time directly. The caller supplies budget aggregates for the context's UTC week.
 */
public final class PolicyEngine {

  private final long hardCapMinor;

  /**
   * @param hardCapMinor platform-wide maximum payment, in minor units
   */
  public PolicyEngine(long hardCapMinor) {
    if (hardCapMinor <= 0) {
      throw new IllegalArgumentException("hardCapMinor must be positive");
    }
    this.hardCapMinor = hardCapMinor;
  }

  /**
   * Evaluates every hard rule in the fixed order and collects all violations.
   *
   * <p>The first reason in a {@code DENY} is the primary reason. When no hard rule fails, an amount
   * at or below the auto-approve threshold is approved; anything higher is escalated.
   */
  public PolicyDecision evaluate(PolicyContext context) {
    Objects.requireNonNull(context, "context is required");
    AgentPolicy policy = context.policy();
    List<PolicyReason> reasons = new ArrayList<>(6);

    if (context.agentStatus() != AgentStatus.ACTIVE) {
      reasons.add(PolicyReason.of(PolicyReasonCode.AGENT_SUSPENDED));
    }
    if (!isSupportedCurrency(context.currency())) {
      reasons.add(PolicyReason.of(PolicyReasonCode.CURRENCY_NOT_SUPPORTED));
    }
    if (context.amountMinor() <= 0) {
      reasons.add(PolicyReason.of(PolicyReasonCode.INVALID_AMOUNT));
    }
    if (context.amountMinor() > hardCapMinor) {
      reasons.add(PolicyReason.of(PolicyReasonCode.AMOUNT_EXCEEDS_CAP));
    }
    if (!policy.allowsPayee(context.payeeEmail())) {
      reasons.add(PolicyReason.of(PolicyReasonCode.PAYEE_NOT_ALLOWED));
    }
    if (exceedsWeeklyBudget(context, policy)) {
      reasons.add(PolicyReason.of(PolicyReasonCode.BUDGET_EXCEEDED));
    }

    BudgetPeriod budgetPeriod = context.budgetPeriod();
    if (!reasons.isEmpty()) {
      return new PolicyDecision(PolicyOutcome.DENY, reasons, budgetPeriod);
    }

    PolicyOutcome outcome =
        context.amountMinor() <= policy.autoApproveThresholdMinor()
            ? PolicyOutcome.APPROVE
            : PolicyOutcome.ESCALATE;
    return new PolicyDecision(outcome, List.of(), budgetPeriod);
  }

  private static boolean isSupportedCurrency(String currency) {
    return currency != null
        && SupportedCurrency.USD.name().equals(currency.trim().toUpperCase(Locale.ROOT));
  }

  private static boolean exceedsWeeklyBudget(PolicyContext context, AgentPolicy policy) {
    long amount = context.amountMinor();
    if (amount <= 0) {
      return false;
    }
    // spent and reserved are non-negative; saturate so an extreme aggregate cannot wrap around.
    long committed = saturatingAdd(context.spentMinor(), context.reservedMinor());
    long available = policy.weeklyLimitMinor() - committed;
    return amount > available;
  }

  private static long saturatingAdd(long left, long right) {
    long sum = left + right;
    return sum < 0 ? Long.MAX_VALUE : sum;
  }
}
