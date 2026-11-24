package com.leash.payment.app;

import com.leash.payment.domain.PaymentRecord;
import java.util.Objects;

/**
 * The outcome of a manual PayPal status refresh.
 *
 * <p>{@code refreshed} is true only when PayPal was actually polled. A terminal or pre-submission
 * payment is a no-op with a human-readable reason, because a manual poll cannot resubmit or move a
 * terminal row.
 */
public record PaymentRefreshResult(PaymentRecord payment, boolean refreshed, String reason) {

  public PaymentRefreshResult {
    Objects.requireNonNull(payment, "payment is required");
  }
}
