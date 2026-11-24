package com.leash.ledger.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable business fact in the append-only ledger.
 *
 * <p>{@code id} is {@code null} until the event has been inserted. {@code paymentId} is nullable
 * for agent-level facts such as policy changes. The default record {@code toString} is overridden
 * so a payload is never accidentally written to a log.
 */
public record LedgerEvent(
    Long id,
    UUID agentId,
    UUID paymentId,
    LedgerEventType eventType,
    String actor,
    Map<String, Object> payload,
    Instant createdAt) {

  public LedgerEvent {
    if (id != null && id <= 0) {
      throw new IllegalArgumentException("id must be positive when present");
    }
    Objects.requireNonNull(agentId, "agentId is required");
    Objects.requireNonNull(eventType, "eventType is required");
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("actor is required");
    }
    actor = actor.trim();
    if (actor.length() > 100) {
      throw new IllegalArgumentException("actor must be at most 100 characters");
    }
    Objects.requireNonNull(payload, "payload is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  @Override
  public String toString() {
    return "LedgerEvent[id="
        + id
        + ", agentId="
        + agentId
        + ", paymentId="
        + paymentId
        + ", eventType="
        + eventType
        + ", actor="
        + actor
        + ", createdAt="
        + createdAt
        + "]";
  }
}
