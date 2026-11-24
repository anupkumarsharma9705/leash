package com.leash.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.common.MutableClock;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.payment.app.PaymentReconciler;
import com.leash.paypal.FakePayPalPayoutsClient;
import com.leash.paypal.PayPalPayoutException;
import com.leash.paypal.PayPalWebhookHeaders;
import com.leash.paypal.PayPalWebhookVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PayPalWebhookPaymentIntegrationTest.TestConfig.class})
class PayPalWebhookPaymentIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final String ALICE = "alice@example.com";

  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;
  @Autowired private FakePayPalPayoutsClient paypal;
  @Autowired private LedgerService ledgerService;
  @Autowired private PaymentReconciler reconciler;
  @Autowired private MutableClock clock;
  @Autowired private ObjectMapper objectMapper;

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }

    @Bean
    @Primary
    FakePayPalPayoutsClient fakePayPalPayoutsClient() {
      return new FakePayPalPayoutsClient();
    }

    @Bean
    @Primary
    PayPalWebhookVerifier alwaysValidWebhookVerifier() {
      return (PayPalWebhookHeaders headers, byte[] rawBody) -> true;
    }
  }

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM webhook_events");
    jdbcTemplate.update("DELETE FROM approvals");
    jdbcTemplate.update("DELETE FROM payment_requests");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM policy_payees");
    jdbcTemplate.update("DELETE FROM policies");
    jdbcTemplate.update("DELETE FROM agents");
    paypal.reset();
    clock.setInstant(NOW);
  }

  @Test
  void itemSucceededWebhookMovesSubmittedPaymentToSucceeded() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-success");

    postItemWebhook(
            "WH-SUCCESS", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", paymentId, "fake-batch-1", "SUCCESS")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(rawStatus(paymentId)).isEqualTo("SUCCESS");
    assertThat(webhookStatusEvents(paymentId)).hasSize(1);
    LedgerEvent event = webhookStatusEvents(paymentId).getFirst();
    assertThat(event.actor()).isEqualTo("paypal-webhook");
    assertThat(event.payload())
        .containsEntry("previousState", "SUBMITTED")
        .containsEntry("state", "SUCCEEDED")
        .containsEntry("rawItemStatus", "SUCCESS")
        .containsEntry("payoutBatchId", "fake-batch-1")
        .containsEntry("eventType", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED");
    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spentMinor").value(1_200))
        .andExpect(jsonPath("$.remainingMinor").value(48_800));
  }

  @Test
  void itemFailedWebhookMovesSubmittedPaymentToFailedAndReleasesBudget() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-failed");

    postItemWebhook("WH-FAILED", "PAYMENT.PAYOUTS-ITEM.FAILED", paymentId, "fake-batch-1", "FAILED")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("FAILED");
    assertThat(rawStatus(paymentId)).isEqualTo("FAILED");
    assertThat(webhookStatusEvents(paymentId).getFirst().payload())
        .containsEntry("state", "FAILED")
        .containsEntry("rawItemStatus", "FAILED");
    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spentMinor").value(0))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(50_000));
  }

  @Test
  void pendingItemWebhookKeepsPaymentSubmittedAndRefreshesItsRawStatus() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-pending");

    postItemWebhook(
            "WH-PENDING", "PAYMENT.PAYOUTS-ITEM.UNCLAIMED", paymentId, "fake-batch-1", "UNCLAIMED")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(rawStatus(paymentId)).isEqualTo("UNCLAIMED");
    assertThat(webhookStatusEvents(paymentId).getFirst().payload())
        .containsEntry("previousState", "SUBMITTED")
        .containsEntry("state", "SUBMITTED")
        .containsEntry("rawItemStatus", "UNCLAIMED");
  }

  @Test
  void duplicateEventIdIsAcknowledgedButAppliedOnlyOnce() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-duplicate");

    postItemWebhook(
            "WH-DUPLICATE", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", paymentId, "fake-batch-1", "SUCCESS")
        .andExpect(status().isOk());
    postItemWebhook(
            "WH-DUPLICATE", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", paymentId, "fake-batch-1", "SUCCESS")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(webhookStatusEvents(paymentId)).hasSize(1);
  }

  @Test
  void lateFailureCannotRegressASucceededPayment() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-late-failure");
    postItemWebhook(
            "WH-FIRST-SUCCESS",
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            paymentId,
            "fake-batch-1",
            "SUCCESS")
        .andExpect(status().isOk());

    postItemWebhook(
            "WH-LATE-FAILURE", "PAYMENT.PAYOUTS-ITEM.FAILED", paymentId, "fake-batch-1", "FAILED")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(rawStatus(paymentId)).isEqualTo("SUCCESS");
    assertThat(webhookStatusEvents(paymentId)).hasSize(1);
  }

  @Test
  void lateSuccessCannotRegressAFailedPayment() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-late-success");
    postItemWebhook(
            "WH-FIRST-FAILURE", "PAYMENT.PAYOUTS-ITEM.FAILED", paymentId, "fake-batch-1", "FAILED")
        .andExpect(status().isOk());

    postItemWebhook(
            "WH-LATE-SUCCESS",
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            paymentId,
            "fake-batch-1",
            "SUCCESS")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("FAILED");
    assertThat(rawStatus(paymentId)).isEqualTo("FAILED");
    assertThat(webhookStatusEvents(paymentId)).hasSize(1);
  }

  @Test
  void batchEventIsAcknowledgedButCannotMoveThePayment() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = createSubmittedPayment(agent, "invoice-webhook-batch");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", "WH-BATCH");
    body.put("event_type", "PAYMENT.PAYOUTSBATCH.SUCCESS");
    body.put(
        "resource",
        Map.of(
            "payout_batch_id",
            "fake-batch-1",
            "batch_status",
            "SUCCESS",
            "payout_item",
            Map.of("sender_item_id", paymentId.toString())));

    mockMvc
        .perform(
            post("/webhooks/paypal")
                .header("PAYPAL-TRANSMISSION-ID", "tx-WH-BATCH")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(webhookStatusEvents(paymentId)).isEmpty();
    assertThat(processedAt("WH-BATCH")).isNotNull();
  }

  @Test
  void unknownPaymentIsAcknowledgedAndMarkedProcessed() throws Exception {
    postItemWebhook(
            "WH-UNKNOWN",
            "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
            UUID.randomUUID(),
            "batch-unknown",
            "SUCCESS")
        .andExpect(status().isOk());

    assertThat(processedAt("WH-UNKNOWN")).isNotNull();
  }

  @Test
  void webhookArrivingBeforeSubmissionIsRetriedAfterTheReconcilerSubmits() throws Exception {
    CreatedAgent agent = newAgent();
    paypal.failNextSubmission(new PayPalPayoutException("Simulated crash", null, null));
    UUID paymentId = createPayment(agent, "invoice-webhook-race", "APPROVED");

    postItemWebhook(
            "WH-RACE", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", paymentId, "fake-batch-1", "SUCCESS")
        .andExpect(status().isInternalServerError());
    assertThat(processedAt("WH-RACE")).isNull();
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");

    paypal.clearFailure();
    clock.advance(Duration.ofSeconds(61));
    reconciler.reconcileOnce();
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");

    // PayPal's retry of the same event id applies it now that TX-B has committed.
    postItemWebhook(
            "WH-RACE", "PAYMENT.PAYOUTS-ITEM.SUCCEEDED", paymentId, "fake-batch-1", "SUCCESS")
        .andExpect(status().isOk());

    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(processedAt("WH-RACE")).isNotNull();
    assertThat(webhookStatusEvents(paymentId)).hasSize(1);
  }

  private CreatedAgent newAgent() {
    return agentService.createAgent("Webhook Agent", 50_000, 10_000, List.of(ALICE));
  }

  private UUID createSubmittedPayment(CreatedAgent agent, String idempotencyKey) throws Exception {
    return createPayment(agent, idempotencyKey, "SUBMITTED");
  }

  private UUID createPayment(CreatedAgent agent, String idempotencyKey, String expectedState)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payeeEmail", ALICE);
    body.put("amountMinor", 1_200);
    body.put("currency", "USD");
    body.put("memo", "Invoice " + idempotencyKey);

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value(expectedState));

    return jdbcTemplate.queryForObject(
        "SELECT id FROM payment_requests WHERE idempotency_key = ?", UUID.class, idempotencyKey);
  }

  private ResultActions postItemWebhook(
      String eventId, String eventType, UUID paymentId, String payoutBatchId, String itemStatus)
      throws Exception {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("payout_batch_id", payoutBatchId);
    resource.put("transaction_status", itemStatus);
    resource.put("payout_item", Map.of("sender_item_id", paymentId.toString()));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", eventId);
    body.put("event_type", eventType);
    body.put("resource", resource);
    return mockMvc.perform(
        post("/webhooks/paypal")
            .header("PAYPAL-TRANSMISSION-ID", "tx-" + eventId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private String paymentState(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private String rawStatus(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT raw_status FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private Object processedAt(String eventId) {
    return jdbcTemplate.queryForObject(
        "SELECT processed_at FROM webhook_events WHERE event_id = ?", Object.class, eventId);
  }

  private List<LedgerEvent> webhookStatusEvents(UUID paymentId) {
    return ledgerService.eventsForPayment(paymentId).stream()
        .filter(event -> event.eventType() == LedgerEventType.PAYPAL_STATUS_CHANGED)
        .filter(event -> "paypal-webhook".equals(event.actor()))
        .toList();
  }
}
