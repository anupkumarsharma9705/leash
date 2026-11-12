package com.leash.payment.app;

import com.leash.payment.domain.PayoutItemWebhook;
import com.leash.webhook.app.PaymentEventApplier;
import com.leash.webhook.domain.WebhookEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Applies verified Payouts item webhooks to the payment state machine. */
@Component
public class PayPalPayoutItemEventApplier implements PaymentEventApplier {

  private static final Logger log = LoggerFactory.getLogger(PayPalPayoutItemEventApplier.class);

  private final PayoutItemWebhookParser parser;
  private final PaymentTransactionService transactions;

  public PayPalPayoutItemEventApplier(
      PayoutItemWebhookParser parser, PaymentTransactionService transactions) {
    this.parser = parser;
    this.transactions = transactions;
  }

  @Override
  public void apply(WebhookEvent event) {
    Optional<PayoutItemWebhook> parsed = parser.parse(event);
    if (parsed.isEmpty()) {
      if (event.eventType().startsWith(PayoutItemWebhookParser.ITEM_EVENT_PREFIX)) {
        log.warn(
            "Ignoring Payouts item webhook without a usable Leash payment id:"
                + " eventId={} eventType={}",
            event.eventId(),
            event.eventType());
      }
      return;
    }
    transactions.applyPayoutItemWebhook(parsed.get());
  }
}
