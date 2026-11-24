package com.leash.webhook.app;

import com.leash.webhook.domain.WebhookEvent;
import java.time.Instant;

/** Persistence boundary for verified webhook events. */
public interface WebhookEventStore {

  /** Returns {@code true} when the event was inserted, {@code false} for a duplicate id. */
  boolean insertIfAbsent(WebhookEvent event);

  boolean isProcessed(String eventId);

  void markProcessed(String eventId, Instant processedAt);
}
