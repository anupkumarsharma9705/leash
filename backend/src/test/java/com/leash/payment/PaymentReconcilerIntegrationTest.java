package com.leash.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.common.MutableClock;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.payment.app.PaymentReconciler;
import com.leash.payment.app.ReconciliationSummary;
import com.leash.paypal.FakePayPalPayoutsClient;
import com.leash.paypal.PayPalPayoutException;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "leash.reconciler.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentReconcilerIntegrationTest.TestConfig.class})
class PaymentReconcilerIntegrationTest {

  private static final Instant START = Instant.parse("2026-11-11T12:00:00Z");
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
      return new MutableClock(START);
    }

    @Bean
    @Primary
    FakePayPalPayoutsClient fakePayPalPayoutsClient() {
      return new FakePayPalPayoutsClient();
    }
  }

  @BeforeEach
  void resetState() {
    jdbcTemplate.update("DELETE FROM approvals");
    jdbcTemplate.update("DELETE FROM payment_requests");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM policy_payees");
    jdbcTemplate.update("DELETE FROM policies");
    jdbcTemplate.update("DELETE FROM agents");
    paypal.reset();
    clock.setInstant(START);
  }

  @Test
  void reconcilesACrashBetweenTheDecisionAndThePayPalSubmission() throws Exception {
    CreatedAgent agent = newAgent();
    paypal.failNextSubmission(new PayPalPayoutException("Simulated crash", null, null));

    UUID paymentId = submitPayment(agent, "invoice-crash", 1_200);

    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    assertThat(paypal.submissions()).isEmpty();

    paypal.clearFailure();
    clock.advance(Duration.ofSeconds(61));

    ReconciliationSummary summary = reconciler.reconcileOnce();

    assertThat(summary.approvedRetried()).isEqualTo(1);
    assertThat(summary.statusPolled()).isZero();
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(paypal.submissions()).hasSize(1);
    assertThat(paypalBatchId(paymentId)).isEqualTo("fake-batch-1");
    assertThat(eventTypes(paymentId))
        .containsExactly(
            LedgerEventType.PAYMENT_REQUESTED,
            LedgerEventType.DECISION_MADE,
            LedgerEventType.PAYPAL_SUBMITTED,
            LedgerEventType.RECONCILED);

    LedgerEvent reconciled = eventOfType(paymentId, LedgerEventType.RECONCILED);
    assertThat(reconciled.actor()).isEqualTo("reconciler");
    assertThat(reconciled.payload())
        .containsEntry("action", "RETRY_SUBMIT")
        .containsEntry("previousState", "APPROVED")
        .containsEntry("state", "SUBMITTED")
        .containsEntry("payoutBatchId", "fake-batch-1");

    // A second pass must not submit the now-SUBMITTED payment a second time.
    reconciler.reconcileOnce();
    assertThat(paypal.submissions()).hasSize(1);
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
  }

  @Test
  void pollsAStaleSubmittedPaymentAndMarksItSucceeded() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = submitPayment(agent, "invoice-success", 1_200);
    String batchId = paypalBatchId(paymentId);
    paypal.setStatus(batchId, "SUCCESS", "SUCCESS");
    clock.advance(Duration.ofMinutes(11));

    ReconciliationSummary summary = reconciler.reconcileOnce();

    assertThat(summary.statusPolled()).isEqualTo(1);
    assertThat(summary.terminalTransitions()).isEqualTo(1);
    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(rawStatus(paymentId)).isEqualTo("SUCCESS");
    assertThat(eventOfType(paymentId, LedgerEventType.RECONCILED).payload())
        .containsEntry("action", "POLL")
        .containsEntry("state", "SUCCEEDED");
    assertThat(eventOfType(paymentId, LedgerEventType.RECONCILED).actor()).isEqualTo("reconciler");

    // A terminal state never moves, even if PayPal later returns a contradictory status.
    paypal.setStatus(batchId, "SUCCESS", "RETURNED");
    clock.advance(Duration.ofMinutes(11));
    summary = reconciler.reconcileOnce();

    assertThat(summary.statusPolled()).isZero();
    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
  }

  @Test
  void marksAStaleSubmittedPaymentFailedAndReleasesItsBudget() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = submitPayment(agent, "invoice-returned", 1_200);
    paypal.setStatus(paypalBatchId(paymentId), "SUCCESS", "RETURNED");
    clock.advance(Duration.ofMinutes(11));

    ReconciliationSummary summary = reconciler.reconcileOnce();

    assertThat(summary.terminalTransitions()).isEqualTo(1);
    assertThat(paymentState(paymentId)).isEqualTo("FAILED");
    assertThat(committedMinor()).isZero();
    assertThat(eventOfType(paymentId, LedgerEventType.RECONCILED).payload())
        .containsEntry("action", "POLL")
        .containsEntry("state", "FAILED")
        .containsEntry("rawItemStatus", "RETURNED");
  }

  @Test
  void leavesAPendingPayoutSubmittedAndRefreshesThePollClock() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = submitPayment(agent, "invoice-pending", 1_200);
    paypal.setStatus(paypalBatchId(paymentId), "PENDING", "PENDING");
    clock.advance(Duration.ofMinutes(11));

    ReconciliationSummary summary = reconciler.reconcileOnce();

    assertThat(summary.statusPolled()).isEqualTo(1);
    assertThat(summary.terminalTransitions()).isZero();
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(rawStatus(paymentId)).isEqualTo("PENDING");
    assertThat(eventOfType(paymentId, LedgerEventType.RECONCILED).payload())
        .containsEntry("action", "POLL")
        .containsEntry("state", "SUBMITTED");

    // The poll refreshed updated_at, so an immediate second pass must not poll again.
    long reconciledEvents = reconciledCount(paymentId);
    summary = reconciler.reconcileOnce();
    assertThat(summary.statusPolled()).isZero();
    assertThat(reconciledCount(paymentId)).isEqualTo(reconciledEvents);
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
  }

  @Test
  void doesNotPollBeforeTheTenMinuteThreshold() throws Exception {
    CreatedAgent agent = newAgent();
    UUID paymentId = submitPayment(agent, "invoice-young", 1_200);
    paypal.setStatus(paypalBatchId(paymentId), "SUCCESS", "SUCCESS");

    clock.advance(Duration.ofMinutes(5));
    assertThat(reconciler.reconcileOnce().statusPolled()).isZero();
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");

    clock.advance(Duration.ofMinutes(6));
    assertThat(reconciler.reconcileOnce().statusPolled()).isEqualTo(1);
    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
  }

  @Test
  void marksAFailedRetryAsFailedAndRecordsTheRepair() throws Exception {
    CreatedAgent agent = newAgent();
    paypal.failNextSubmission(new PayPalPayoutException("Simulated crash", null, null));
    UUID paymentId = submitPayment(agent, "invoice-retry-rejected", 1_200);
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");

    paypal.failNextSubmission(
        new PayPalPayoutException("PayPal rejected the retry", 400, "debug-retry"));
    clock.advance(Duration.ofSeconds(61));

    ReconciliationSummary summary = reconciler.reconcileOnce();

    assertThat(summary.approvedRetried()).isEqualTo(1);
    assertThat(paymentState(paymentId)).isEqualTo("FAILED");
    assertThat(committedMinor()).isZero();
    assertThat(eventOfType(paymentId, LedgerEventType.PAYPAL_STATUS_CHANGED).actor())
        .isEqualTo("reconciler");
    assertThat(eventOfType(paymentId, LedgerEventType.RECONCILED).payload())
        .containsEntry("action", "RETRY_SUBMIT_FAILED")
        .containsEntry("state", "FAILED")
        .containsEntry("httpStatus", 400);
  }

  @Test
  void theScheduledEntryPointRespectsTheDisabledFlag() throws Exception {
    CreatedAgent agent = newAgent();
    paypal.failNextSubmission(new PayPalPayoutException("Simulated crash", null, null));
    UUID paymentId = submitPayment(agent, "invoice-disabled", 1_200);
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");

    paypal.clearFailure();
    clock.advance(Duration.ofMinutes(5));

    reconciler.scheduledReconcile();

    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    assertThat(paypal.submissions()).isEmpty();
  }

  private CreatedAgent newAgent() {
    return agentService.createAgent("Reconciler Agent", 50_000, 10_000, List.of(ALICE));
  }

  private UUID submitPayment(CreatedAgent agent, String idempotencyKey, long amountMinor)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payeeEmail", ALICE);
    body.put("amountMinor", amountMinor);
    body.put("currency", "USD");
    body.put("memo", "Invoice " + idempotencyKey);

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated());

    return jdbcTemplate.queryForObject(
        "SELECT id FROM payment_requests WHERE idempotency_key = ?", UUID.class, idempotencyKey);
  }

  private String paymentState(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private String rawStatus(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT raw_status FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private String paypalBatchId(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT paypal_batch_id FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private long committedMinor() {
    Long committed =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_minor), 0) FROM payment_requests"
                + " WHERE state IN ('APPROVED', 'SUBMITTED', 'SUCCEEDED')",
            Long.class);
    return committed == null ? 0 : committed;
  }

  private List<LedgerEventType> eventTypes(UUID paymentId) {
    return ledgerService.eventsForPayment(paymentId).stream().map(LedgerEvent::eventType).toList();
  }

  private long reconciledCount(UUID paymentId) {
    return ledgerService.eventsForPayment(paymentId).stream()
        .filter(event -> event.eventType() == LedgerEventType.RECONCILED)
        .count();
  }

  private LedgerEvent eventOfType(UUID paymentId, LedgerEventType type) {
    return ledgerService.eventsForPayment(paymentId).stream()
        .filter(event -> event.eventType() == type)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing ledger event " + type));
  }
}
