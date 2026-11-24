package com.leash.webhook.app;

import com.leash.webhook.domain.WebhookEvent;
import com.leash.webhook.domain.WebhookRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses the event envelope only after PayPal has verified the signature.
 *
 * <p>Callers keep the original bytes separately; this parser never rewrites the event.
 */
@Component
public class WebhookEventParser {

  private final ObjectMapper objectMapper;

  public WebhookEventParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public WebhookEvent parse(byte[] rawBody, Instant receivedAt) {
    JsonNode root;
    try {
      root = objectMapper.readTree(rawBody);
    } catch (JacksonException exception) {
      throw new WebhookRejectedException("INVALID_WEBHOOK_EVENT", "Webhook body is not valid JSON");
    }
    if (root == null || !root.isObject()) {
      throw new WebhookRejectedException(
          "INVALID_WEBHOOK_EVENT", "Webhook body must be a JSON object");
    }

    String eventId = text(root, "id");
    String eventType = text(root, "event_type");
    if (eventId == null || eventType == null) {
      throw new WebhookRejectedException(
          "INVALID_WEBHOOK_EVENT", "Webhook body must contain id and event_type");
    }

    return new WebhookEvent(
        eventId, eventType, new String(rawBody, StandardCharsets.UTF_8), receivedAt);
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      return null;
    }
    return value.asString();
  }
}
