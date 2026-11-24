package com.leash.payment.api;

import com.leash.payment.app.ApprovalDecision;
import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.ApprovalStatus;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PendingApproval;
import com.leash.policy.PolicyReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One approval, either still pending or already decided.
 *
 * <p>The response carries the payment facts an approver needs to make the call (payee, amount,
 * memo) without a second request. {@code autoRejected} and {@code rejectionReasons} are populated
 * when the approve re-check denied the payment.
 */
public record AdminApprovalResponse(
    UUID approvalId,
    UUID paymentId,
    UUID agentId,
    ApprovalStatus status,
    PaymentState paymentState,
    String payeeEmail,
    long amountMinor,
    String currency,
    String memo,
    Instant requestedAt,
    Instant decidedAt,
    String decidedBy,
    String note,
    boolean replayed,
    boolean autoRejected,
    List<PolicyReason> rejectionReasons) {

  public AdminApprovalResponse {
    rejectionReasons = List.copyOf(rejectionReasons);
  }

  public static AdminApprovalResponse pending(PendingApproval pending) {
    ApprovalRecord approval = pending.approval();
    PaymentRecord payment = pending.payment();
    return new AdminApprovalResponse(
        approval.id(),
        payment.id(),
        payment.agentId(),
        approval.status(),
        payment.state(),
        payment.payeeEmail(),
        payment.amountMinor(),
        payment.currency(),
        payment.memo(),
        approval.requestedAt(),
        approval.decidedAt(),
        approval.decidedBy(),
        approval.note(),
        false,
        false,
        List.of());
  }

  public static AdminApprovalResponse decided(ApprovalDecision decision) {
    ApprovalRecord approval = decision.approval();
    PaymentRecord payment = decision.payment();
    return new AdminApprovalResponse(
        approval.id(),
        payment.id(),
        payment.agentId(),
        approval.status(),
        payment.state(),
        payment.payeeEmail(),
        payment.amountMinor(),
        payment.currency(),
        payment.memo(),
        approval.requestedAt(),
        approval.decidedAt(),
        approval.decidedBy(),
        approval.note(),
        decision.replayed(),
        decision.autoRejected(),
        decision.rejectionReasons());
  }
}
