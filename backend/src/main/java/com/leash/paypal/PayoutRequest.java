package com.leash.paypal;

import com.leash.common.money.Money;

/** A single payout command: one payment request, one recipient, one item. */
public record PayoutRequest(String paymentId, String payeeEmail, Money amount, String memo) {

  public PayoutRequest {
    if (paymentId == null || paymentId.isBlank()) {
      throw new IllegalArgumentException("paymentId is required");
    }
    if (payeeEmail == null || payeeEmail.isBlank()) {
      throw new IllegalArgumentException("payeeEmail is required");
    }
    if (amount == null) {
      throw new IllegalArgumentException("amount is required");
    }
  }
}
