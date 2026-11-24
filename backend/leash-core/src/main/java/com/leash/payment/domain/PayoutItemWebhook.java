package com.leash.payment.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One verified PayPal Payouts item webhook, reduced to the fields Leash needs.
 *
 * <p>{@code sender_item_id} is the Leash payment id: it is set on submission and PayPal echoes it
 * back on every item event. The raw item status is kept because PayPal's vocabulary is stored for
 * audit rather than translated on the way in.
 */
public record PayoutItemWebhook(
    UUID paymentId, String payoutBatchId, String rawItemStatus, String eventType) {

  public PayoutItemWebhook {
    Objects.requireNonNull(paymentId, "paymentId is required");
    Objects.requireNonNull(eventType, "eventType is required");
  }
}
