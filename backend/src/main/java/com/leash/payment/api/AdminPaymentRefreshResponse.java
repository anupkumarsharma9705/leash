package com.leash.payment.api;

import com.leash.payment.app.PaymentRefreshResult;
import com.leash.payment.domain.PaymentState;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of a manual PayPal poll.
 *
 * <p>{@code refreshed} distinguishes "PayPal was polled" from "nothing to do"; {@code reason}
 * explains a no-op. The console can use either to show a precise toast without refetching a
 * different endpoint.
 */
public record AdminPaymentRefreshResponse(
    UUID paymentId,
    PaymentState state,
    String paypalBatchId,
    String rawStatus,
    Instant updatedAt,
    boolean refreshed,
    String reason) {

  public static AdminPaymentRefreshResponse from(PaymentRefreshResult result) {
    return new AdminPaymentRefreshResponse(
        result.payment().id(),
        result.payment().state(),
        result.payment().paypalBatchId(),
        result.payment().rawStatus(),
        result.payment().updatedAt(),
        result.refreshed(),
        result.reason());
  }
}
