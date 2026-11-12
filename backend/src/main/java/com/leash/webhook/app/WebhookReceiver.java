package com.leash.webhook.app;

import com.leash.paypal.PayPalWebhookHeaders;
import com.leash.paypal.PayPalWebhookVerifier;
import com.leash.webhook.domain.WebhookEvent;
import com.leash.webhook.domain.WebhookRejectedException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verifies, stores, deduplicates, and applies PayPal webhook deliveries.
 *
 * <p>The raw event is committed before the payment applier runs. If applying fails, the event
 * remains unprocessed and a later delivery of the same event id is retried instead of being
 * discarded as a duplicate.
 */
@Service
public class WebhookReceiver {

  private static final Logger log = LoggerFactory.getLogger(WebhookReceiver.class);

  private final PayPalWebhookVerifier verifier;
  private final WebhookEventParser parser;
  private final WebhookEventStore store;
  private final Optional<PaymentEventApplier> applier;
  private final Clock clock;

  public WebhookReceiver(
      PayPalWebhookVerifier verifier,
      WebhookEventParser parser,
      WebhookEventStore store,
      Optional<PaymentEventApplier> applier,
      Clock clock) {
    this.verifier = verifier;
    this.parser = parser;
    this.store = store;
    this.applier = applier;
    this.clock = clock;
  }

  public WebhookIngestResult receive(PayPalWebhookHeaders headers, byte[] rawBody) {
    boolean verified = verifier.verify(headers, rawBody);
    if (!verified) {
      log.warn(
          "Rejected unverified PayPal webhook: transmissionId={} bodyBytes={}",
          headers == null ? "none" : headers.transmissionId(),
          rawBody == null ? 0 : rawBody.length);
      throw new WebhookRejectedException(
          "WEBHOOK_VERIFICATION_FAILED", "PayPal webhook signature verification failed");
    }

    WebhookEvent event = parser.parse(rawBody, clock.instant());
    boolean inserted = store.insertIfAbsent(event);

    if (!inserted && store.isProcessed(event.eventId())) {
      log.info(
          "Ignoring duplicate PayPal webhook: eventId={} eventType={}",
          event.eventId(),
          event.eventType());
      return WebhookIngestResult.DUPLICATE;
    }

    if (applier.isEmpty()) {
      log.info(
          "Stored PayPal webhook; no payment applier registered yet: eventId={} eventType={}",
          event.eventId(),
          event.eventType());
      return WebhookIngestResult.STORED;
    }

    try {
      applier.get().apply(event);
    } catch (RuntimeException exception) {
      log.error(
          "Failed to apply PayPal webhook; event remains unprocessed: eventId={} eventType={}",
          event.eventId(),
          event.eventType(),
          exception);
      throw new WebhookProcessingException("Unable to apply PayPal webhook", exception);
    }

    store.markProcessed(event.eventId(), clock.instant());
    log.info(
        "{} PayPal webhook: eventId={} eventType={}",
        inserted ? "Processed" : "Replayed",
        event.eventId(),
        event.eventType());
    return inserted ? WebhookIngestResult.PROCESSED : WebhookIngestResult.REPLAYED;
  }
}
