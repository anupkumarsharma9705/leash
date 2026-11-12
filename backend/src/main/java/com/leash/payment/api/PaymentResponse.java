package com.leash.payment.api;

import com.leash.payment.app.PaymentResult;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.util.List;
import java.util.UUID;

/**
 * The stable response body for a payment decision.
 *
 * <p>{@code budget} is the snapshot captured when the decision was made and stored with the
 * payment, so an idempotent replay returns an identical body. It is not a live balance; use {@code
 * GET /api/v1/budget} for the current period.
 */
public record PaymentResponse(
    UUID paymentId, PaymentState state, Decision decision, Budget budget) {

  public static PaymentResponse from(PaymentResult result) {
    return new PaymentResponse(
        result.paymentId(),
        result.state(),
        new Decision(result.outcome(), result.reasons()),
        new Budget(
            result.budget().limitMinor(),
            result.budget().spentMinor(),
            result.budget().reservedMinor(),
            result.budget().remainingMinor()));
  }

  /** A read of a stored payment reuses the create response shape and its stored budget snapshot. */
  public static PaymentResponse from(PaymentRecord record) {
    return from(PaymentResult.from(record, false));
  }

  public record Decision(PolicyOutcome outcome, List<PolicyReason> reasons) {}

  public record Budget(long limitMinor, long spentMinor, long reservedMinor, long remainingMinor) {}
}
