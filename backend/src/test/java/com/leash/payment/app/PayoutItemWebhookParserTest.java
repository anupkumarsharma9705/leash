package com.leash.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.leash.payment.domain.PayoutItemWebhook;
import com.leash.webhook.domain.WebhookEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PayoutItemWebhookParserTest {

  private static final Instant RECEIVED_AT = Instant.parse("2026-11-11T12:00:00Z");
  private static final UUID PAYMENT_ID = UUID.fromString("0f7b8f4e-6d5a-4c3b-9a21-1234567890ab");

  private final PayoutItemWebhookParser parser = new PayoutItemWebhookParser(new ObjectMapper());

  @Test
  void parsesRealPaypalItemShape() {
    WebhookEvent event =
        event(
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            """
            {
              "payout_batch_id": "batch-1",
              "transaction_status": "SUCCESS",
              "payout_item": {
                "sender_item_id": "%s"
              }
            }
            """
                .formatted(PAYMENT_ID));

    Optional<PayoutItemWebhook> parsed = parser.parse(event);

    assertThat(parsed)
        .contains(
            new PayoutItemWebhook(
                PAYMENT_ID, "batch-1", "SUCCESS", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED"));
  }

  @Test
  void acceptsSenderItemIdDirectlyOnResource() {
    WebhookEvent event =
        event(
            "PAYMENT.PAYOUTS-ITEM.FAILED",
            """
            {"transaction_status":"FAILED","sender_item_id":"%s"}
            """
                .formatted(PAYMENT_ID));

    assertThat(parser.parse(event))
        .get()
        .extracting(
            PayoutItemWebhook::paymentId,
            PayoutItemWebhook::payoutBatchId,
            PayoutItemWebhook::rawItemStatus)
        .containsExactly(PAYMENT_ID, null, "FAILED");
  }

  @Test
  void derivesSuccessFromEventTypeWhenStatusIsMissing() {
    WebhookEvent event =
        event(
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            """
            {"payout_item":{"sender_item_id":"%s"}}
            """
                .formatted(PAYMENT_ID));

    assertThat(parser.parse(event))
        .get()
        .extracting(PayoutItemWebhook::rawItemStatus)
        .isEqualTo("SUCCESS");
  }

  @Test
  void transactionStatusWinsOverEventType() {
    WebhookEvent event =
        event(
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            """
            {"transaction_status":"PENDING","payout_item":{"sender_item_id":"%s"}}
            """
                .formatted(PAYMENT_ID));

    assertThat(parser.parse(event))
        .get()
        .extracting(PayoutItemWebhook::rawItemStatus)
        .isEqualTo("PENDING");
  }

  @Test
  void ignoresBatchEventsAndUnrelatedEvents() {
    assertThat(parser.parse(event("PAYMENT.PAYOUTSBATCH.SUCCESS", "{}"))).isEmpty();
    assertThat(parser.parse(event("CUSTOMER.DISPUTE.CREATED", "{}"))).isEmpty();
  }

  @Test
  void ignoresItemEventsWithoutAUsablePaymentId() {
    assertThat(
            parser.parse(
                event(
                    "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
                    """
                    {"transaction_status":"SUCCESS"}
                    """)))
        .isEmpty();
    assertThat(
            parser.parse(
                event(
                    "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
                    """
                    {"payout_item":{"sender_item_id":"pay_123"}}
                    """)))
        .isEmpty();
  }

  @Test
  void ignoresMalformedJson() {
    assertThat(
            parser.parse(
                new WebhookEvent(
                    "WH-malformed", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", "not-json", RECEIVED_AT)))
        .isEmpty();
  }

  private static WebhookEvent event(String eventType, String resource) {
    return new WebhookEvent(
        "WH-" + UUID.randomUUID(),
        eventType,
        "{\"id\":\"WH-1\",\"event_type\":\"" + eventType + "\",\"resource\":" + resource + "}",
        RECEIVED_AT);
  }
}
