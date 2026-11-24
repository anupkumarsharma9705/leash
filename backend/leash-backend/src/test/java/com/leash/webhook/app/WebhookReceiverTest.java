package com.leash.webhook.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leash.payment.domain.PaymentState;
import com.leash.paypal.PayPalWebhookHeaders;
import com.leash.paypal.PayPalWebhookVerifier;
import com.leash.webhook.domain.WebhookEvent;
import com.leash.webhook.domain.WebhookRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WebhookReceiverTest {

  private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void acceptsVerifiedEventAndStoresExactRawBody() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    CountingApplier applier = new CountingApplier();
    byte[] body = event("WH-1", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "pay_123");

    WebhookIngestResult result =
        receiver((headers, raw) -> true, store, Optional.of(applier)).receive(headers(), body);

    assertThat(result).isEqualTo(WebhookIngestResult.PROCESSED);
    assertThat(store.rawBodyFor("WH-1")).isEqualTo(new String(body, StandardCharsets.UTF_8));
    assertThat(store.isProcessed("WH-1")).isTrue();
    assertThat(applier.calls.get()).isEqualTo(1);
  }

  @Test
  void rejectsTamperedBodyWithoutPersistingAnything() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    CountingApplier applier = new CountingApplier();
    byte[] tampered = event("WH-2", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "pay_123");

    WebhookReceiver receiver = receiver((headers, raw) -> false, store, Optional.of(applier));

    assertThatThrownBy(() -> receiver.receive(headers(), tampered))
        .isInstanceOfSatisfying(
            WebhookRejectedException.class,
            exception -> assertThat(exception.code()).isEqualTo("WEBHOOK_VERIFICATION_FAILED"));
    assertThat(store.size()).isZero();
    assertThat(applier.calls.get()).isZero();
  }

  @Test
  void ignoresDuplicateEventThatWasAlreadyProcessed() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    byte[] body = event("WH-3", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "pay_123");
    store.seed(
        new WebhookEvent(
            "WH-3",
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            new String(body, StandardCharsets.UTF_8),
            NOW),
        true);
    CountingApplier applier = new CountingApplier();

    WebhookIngestResult result =
        receiver((headers, raw) -> true, store, Optional.of(applier)).receive(headers(), body);

    assertThat(result).isEqualTo(WebhookIngestResult.DUPLICATE);
    assertThat(store.size()).isEqualTo(1);
    assertThat(applier.calls.get()).isZero();
  }

  @Test
  void replayedUnprocessedEventStillAppliesOnce() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    byte[] body = event("WH-4", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "pay_123");
    store.seed(
        new WebhookEvent(
            "WH-4",
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            new String(body, StandardCharsets.UTF_8),
            NOW),
        false);
    CountingApplier applier = new CountingApplier();

    WebhookIngestResult result =
        receiver((headers, raw) -> true, store, Optional.of(applier)).receive(headers(), body);

    assertThat(result).isEqualTo(WebhookIngestResult.REPLAYED);
    assertThat(store.isProcessed("WH-4")).isTrue();
    assertThat(applier.calls.get()).isEqualTo(1);
  }

  @Test
  void outOfOrderEventCannotMoveTerminalStateBackward() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    TerminalGuardedApplier applier = new TerminalGuardedApplier();
    applier.states.put("pay_123", PaymentState.SUCCEEDED);
    byte[] lateFailure = event("WH-5", "PAYMENT.PAYOUTS-ITEM.FAILED", "pay_123");

    WebhookIngestResult result =
        receiver((headers, raw) -> true, store, Optional.of(applier))
            .receive(headers(), lateFailure);

    assertThat(result).isEqualTo(WebhookIngestResult.PROCESSED);
    assertThat(applier.states.get("pay_123")).isEqualTo(PaymentState.SUCCEEDED);
  }

  @Test
  void storesEventWhenNoApplierIsRegisteredYet() {
    InMemoryWebhookEventStore store = new InMemoryWebhookEventStore();
    byte[] body = event("WH-6", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "pay_123");

    WebhookIngestResult result =
        receiver((headers, raw) -> true, store, Optional.empty()).receive(headers(), body);

    assertThat(result).isEqualTo(WebhookIngestResult.STORED);
    assertThat(store.isProcessed("WH-6")).isFalse();
  }

  private static WebhookReceiver receiver(
      PayPalWebhookVerifier verifier,
      InMemoryWebhookEventStore store,
      Optional<PaymentEventApplier> applier) {
    return new WebhookReceiver(
        verifier, new WebhookEventParser(new ObjectMapper()), store, applier, CLOCK);
  }

  private static PayPalWebhookHeaders headers() {
    return new PayPalWebhookHeaders(
        "tx-1",
        "2026-11-10T00:00:00Z",
        "signature",
        "https://api.paypal.com/cert.pem",
        "SHA256withRSA");
  }

  private static byte[] event(String id, String eventType, String senderItemId) {
    return ("{\"id\":\""
            + id
            + "\",\"event_type\":\""
            + eventType
            + "\",\"resource\":{\"sender_item_id\":\""
            + senderItemId
            + "\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static final class InMemoryWebhookEventStore implements WebhookEventStore {
    private final Map<String, WebhookEvent> events = new HashMap<>();
    private final Set<String> processed = new HashSet<>();

    @Override
    public boolean insertIfAbsent(WebhookEvent event) {
      return events.putIfAbsent(event.eventId(), event) == null;
    }

    @Override
    public boolean isProcessed(String eventId) {
      return processed.contains(eventId);
    }

    @Override
    public void markProcessed(String eventId, Instant processedAt) {
      processed.add(eventId);
    }

    void seed(WebhookEvent event, boolean alreadyProcessed) {
      events.put(event.eventId(), event);
      if (alreadyProcessed) {
        processed.add(event.eventId());
      }
    }

    int size() {
      return events.size();
    }

    String rawBodyFor(String eventId) {
      return events.get(eventId).rawBody();
    }
  }

  private static final class CountingApplier implements PaymentEventApplier {
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public void apply(WebhookEvent event) {
      calls.incrementAndGet();
    }
  }

  /**
   * Stand-in for the Phase 2 payment applier. It models the terminal-state guard that the real
   * state machine will enforce: a terminal payment never moves again.
   */
  private static final class TerminalGuardedApplier implements PaymentEventApplier {
    private final Map<String, PaymentState> states = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void apply(WebhookEvent event) {
      String paymentId =
          objectMapper.readTree(event.rawBody()).path("resource").path("sender_item_id").asString();
      PaymentState current = states.get(paymentId);
      if (current == PaymentState.SUCCEEDED || current == PaymentState.FAILED) {
        return;
      }
      PaymentState target =
          event.eventType().endsWith(".SUCCEEDED") ? PaymentState.SUCCEEDED : PaymentState.FAILED;
      states.put(paymentId, target);
    }
  }
}
