package com.leash.webhook.domain;

import java.time.Instant;

/** A verified PayPal webhook event, with its original JSON text preserved for audit. */
public record WebhookEvent(String eventId, String eventType, String rawBody, Instant receivedAt) {

  public WebhookEvent {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("eventId is required");
    }
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType is required");
    }
    if (rawBody == null || rawBody.isBlank()) {
      throw new IllegalArgumentException("rawBody is required");
    }
    if (receivedAt == null) {
      throw new IllegalArgumentException("receivedAt is required");
    }
  }
}
