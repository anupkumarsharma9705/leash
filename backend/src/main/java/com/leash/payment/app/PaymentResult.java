package com.leash.payment.app;

import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.util.List;
import java.util.UUID;

/** The stable payment response returned to an agent. */
public record PaymentResult(
    UUID paymentId,
    PaymentState state,
    PolicyOutcome outcome,
    List<PolicyReason> reasons,
    BudgetSnapshot budget,
    boolean replayed) {

  public PaymentResult {
    reasons = List.copyOf(reasons);
  }

  public static PaymentResult from(PaymentRecord record, boolean replayed) {
    return new PaymentResult(
        record.id(),
        record.state(),
        record.decisionOutcome(),
        record.decisionReasons(),
        record.budget(),
        replayed);
  }
}
