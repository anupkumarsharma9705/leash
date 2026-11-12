package com.leash.payment.app;

import com.leash.payment.domain.PayoutItemWebhook;
import com.leash.webhook.domain.WebhookEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts the Leash payment id and item status from a verified Payouts item webhook.
 *
 * <p>Only {@code PAYMENT.PAYOUTS-ITEM.*} events can move a payment. Batch events are ignored: a
 * batch result is not proof that any individual item reached its recipient. The parser is
 * deliberately forgiving about unknown envelope fields and returns empty for events it cannot
 * attribute to a payment, so a malformed event is acknowledged and never retried forever.
 */
@Component
public class PayoutItemWebhookParser {

  static final String ITEM_EVENT_PREFIX = "PAYMENT.PAYOUTS-ITEM.";

  private final ObjectMapper objectMapper;

  public PayoutItemWebhookParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public Optional<PayoutItemWebhook> parse(WebhookEvent event) {
    if (!event.eventType().startsWith(ITEM_EVENT_PREFIX)) {
      return Optional.empty();
    }

    JsonNode root;
    try {
      root = objectMapper.readTree(event.rawBody());
    } catch (JacksonException exception) {
      return Optional.empty();
    }
    JsonNode resource = root.path("resource");
    String senderItemId = text(resource.path("payout_item"), "sender_item_id");
    if (senderItemId == null) {
      // Older fixtures and some PayPal examples put sender_item_id directly on the resource.
      senderItemId = text(resource, "sender_item_id");
    }
    if (senderItemId == null) {
      return Optional.empty();
    }

    UUID paymentId;
    try {
      paymentId = UUID.fromString(senderItemId);
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }

    String rawItemStatus = text(resource, "transaction_status");
    if (rawItemStatus == null) {
      rawItemStatus = statusFromEventType(event.eventType());
    }
    return Optional.of(
        new PayoutItemWebhook(
            paymentId, text(resource, "payout_batch_id"), rawItemStatus, event.eventType()));
  }

  private static String statusFromEventType(String eventType) {
    String suffix = eventType.substring(ITEM_EVENT_PREFIX.length());
    return "SUCCEEDED".equals(suffix) ? "SUCCESS" : suffix;
  }

  private static String text(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      return null;
    }
    return value.asString();
  }
}
