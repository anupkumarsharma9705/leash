package com.leash.payment.domain;

import java.util.Objects;

/**
 * A pending approval joined with the payment it governs.
 *
 * <p>The pair is read in one query so the admin list never issues a payment lookup per approval.
 */
public record PendingApproval(ApprovalRecord approval, PaymentRecord payment) {

  public PendingApproval {
    Objects.requireNonNull(approval, "approval is required");
    Objects.requireNonNull(payment, "payment is required");
    if (approval.status() != ApprovalStatus.PENDING) {
      throw new IllegalArgumentException("Only a pending approval can be listed");
    }
    if (!approval.paymentId().equals(payment.id())) {
      throw new IllegalArgumentException("Approval and payment ids do not match");
    }
    if (payment.state() != PaymentState.PENDING_APPROVAL) {
      throw new IllegalArgumentException("A pending approval requires a PENDING_APPROVAL payment");
    }
  }
}
