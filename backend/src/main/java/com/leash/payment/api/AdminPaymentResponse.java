package com.leash.payment.api;

import com.leash.payment.app.AdminPaymentRow;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One payment row for the administrative ledger grid. */
public record AdminPaymentResponse(
    UUID paymentId,
    UUID agentId,
    String agentName,
    String payeeEmail,
    long amountMinor,
    String currency,
    String memo,
    PaymentState state,
    PolicyOutcome decisionOutcome,
    List<PolicyReason> decisionReasons,
    String paypalBatchId,
    String rawStatus,
    Instant createdAt,
    Instant updatedAt) {

  public AdminPaymentResponse {
    decisionReasons = List.copyOf(decisionReasons);
  }

  public static AdminPaymentResponse from(AdminPaymentRow row) {
    PaymentRecord payment = row.payment();
    return new AdminPaymentResponse(
        payment.id(),
        payment.agentId(),
        row.agentName(),
        payment.payeeEmail(),
        payment.amountMinor(),
        payment.currency(),
        payment.memo(),
        payment.state(),
        payment.decisionOutcome(),
        payment.decisionReasons(),
        payment.paypalBatchId(),
        payment.rawStatus(),
        payment.createdAt(),
        payment.updatedAt());
  }
}
