package com.leash.payment.app;

import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.PaymentRecord;
import com.leash.policy.PolicyReason;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of an approve or reject request.
 *
 * <p>{@code replayed} is true when the same decision had already committed, so the call was a
 * no-op. {@code rejectionReasons} is non-empty only when the approve re-check denied the payment
 * automatically; a human rejection has no policy reasons.
 */
public record ApprovalDecision(
    ApprovalRecord approval,
    PaymentRecord payment,
    boolean replayed,
    List<PolicyReason> rejectionReasons) {

  public ApprovalDecision {
    Objects.requireNonNull(approval, "approval is required");
    Objects.requireNonNull(payment, "payment is required");
    rejectionReasons = List.copyOf(rejectionReasons);
  }

  public boolean autoRejected() {
    return !rejectionReasons.isEmpty();
  }
}
