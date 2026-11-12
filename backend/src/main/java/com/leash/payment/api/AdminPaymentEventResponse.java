package com.leash.payment.api;

import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One append-only ledger fact in a payment timeline.
 *
 * <p>The payload is the stored JSON object verbatim. It is never redacted or reshaped here; ledger
 * writers are responsible for keeping secrets out of it.
 */
public record AdminPaymentEventResponse(
    long id,
    LedgerEventType eventType,
    String actor,
    Map<String, Object> payload,
    Instant createdAt) {

  public AdminPaymentEventResponse {
    payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  public static AdminPaymentEventResponse from(LedgerEvent event) {
    return new AdminPaymentEventResponse(
        event.id(), event.eventType(), event.actor(), event.payload(), event.createdAt());
  }
}
