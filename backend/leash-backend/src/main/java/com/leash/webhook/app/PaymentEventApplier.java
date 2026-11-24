package com.leash.webhook.app;

import com.leash.webhook.domain.WebhookEvent;

/**
 * Applies a verified webhook to the payment domain.
 *
 * <p>Implementations must be idempotent: a retry for an event that was persisted but not marked
 * processed may call this method again.
 */
@FunctionalInterface
public interface PaymentEventApplier {

  void apply(WebhookEvent event);
}
