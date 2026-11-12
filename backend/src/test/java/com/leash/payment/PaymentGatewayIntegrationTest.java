package com.leash.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.common.money.SupportedCurrency;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.paypal.FakePayPalPayoutsClient;
import com.leash.paypal.PayPalOAuthException;
import com.leash.paypal.PayPalPayoutException;
import com.leash.paypal.PayoutRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PaymentGatewayIntegrationTest.TestConfig.class})
class PaymentGatewayIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;
  @Autowired private FakePayPalPayoutsClient paypal;
  @Autowired private LedgerService ledgerService;
  @Autowired private ObjectMapper objectMapper;

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    FakePayPalPayoutsClient fakePayPalPayoutsClient() {
      return new FakePayPalPayoutsClient();
    }
  }

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM approvals");
    jdbcTemplate.update("DELETE FROM payment_requests");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM policy_payees");
    jdbcTemplate.update("DELETE FROM policies");
    jdbcTemplate.update("DELETE FROM agents");
    paypal.reset();
  }

  @Test
  void approvesAndSubmitsAnAmountAtTheAutoApproveThreshold() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    MvcResult result =
        mockMvc
            .perform(
                payment("invoice-1", "Alice@Example.COM", 10_000, "usd", " Invoice inv-1 ", agent))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist(IDEMPOTENT_REPLAYED))
            .andExpect(jsonPath("$.state").value("SUBMITTED"))
            .andExpect(jsonPath("$.decision.outcome").value("APPROVE"))
            .andExpect(jsonPath("$.decision.reasons").isEmpty())
            .andExpect(jsonPath("$.budget.limitMinor").value(50_000))
            .andExpect(jsonPath("$.budget.spentMinor").value(10_000))
            .andExpect(jsonPath("$.budget.reservedMinor").value(0))
            .andExpect(jsonPath("$.budget.remainingMinor").value(40_000))
            .andReturn();

    UUID paymentId = singlePaymentId();
    assertThat(result.getResponse().getContentAsString()).contains(paymentId.toString());
    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT payee_email, amount_minor, currency, memo, decision_outcome,"
                + " paypal_batch_id, raw_status, version"
                + " FROM payment_requests WHERE id = ?",
            paymentId);
    assertThat(row.get("payee_email")).isEqualTo("alice@example.com");
    assertThat(row.get("amount_minor")).isEqualTo(10_000L);
    assertThat(row.get("currency")).isEqualTo("USD");
    assertThat(row.get("memo")).isEqualTo("Invoice inv-1");
    assertThat(row.get("decision_outcome")).isEqualTo("APPROVE");
    assertThat(row.get("paypal_batch_id")).isEqualTo("fake-batch-1");
    assertThat(row.get("raw_status")).isEqualTo("PENDING");
    assertThat(row.get("version")).isEqualTo(1L);

    assertThat(paypal.submissions()).hasSize(1);
    PayoutRequest submitted = paypal.requestFor("fake-batch-1");
    assertThat(submitted.paymentId()).isEqualTo(paymentId.toString());
    assertThat(submitted.payeeEmail()).isEqualTo("alice@example.com");
    assertThat(submitted.amount().amountMinor()).isEqualTo(10_000);
    assertThat(submitted.amount().currency()).isEqualTo(SupportedCurrency.USD);
    assertThat(submitted.memo()).isEqualTo("Invoice inv-1");

    assertThat(eventTypes(paymentId))
        .containsExactly(
            LedgerEventType.PAYMENT_REQUESTED,
            LedgerEventType.DECISION_MADE,
            LedgerEventType.PAYPAL_SUBMITTED);
    assertThat(eventActor(paymentId, LedgerEventType.PAYPAL_SUBMITTED)).isEqualTo("paypal-adapter");
  }

  @Test
  void escalatesAnAmountAboveTheThresholdAndCreatesAPendingApproval() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-2", "alice@example.com", 10_001, "USD", "Invoice inv-2", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.decision.outcome").value("ESCALATE"))
        .andExpect(jsonPath("$.decision.reasons").isEmpty())
        .andExpect(jsonPath("$.budget.spentMinor").value(0))
        .andExpect(jsonPath("$.budget.reservedMinor").value(10_001))
        .andExpect(jsonPath("$.budget.remainingMinor").value(39_999));

    UUID paymentId = singlePaymentId();
    Map<String, Object> approval =
        jdbcTemplate.queryForMap(
            "SELECT status, decided_at, decided_by, note FROM approvals WHERE payment_id = ?",
            paymentId);
    assertThat(approval.get("status")).isEqualTo("PENDING");
    assertThat(approval.get("decided_at")).isNull();
    assertThat(approval.get("decided_by")).isNull();
    assertThat(approval.get("note")).isNull();

    assertThat(paypal.submissions()).isEmpty();
    assertThat(eventTypes(paymentId))
        .containsExactly(
            LedgerEventType.PAYMENT_REQUESTED,
            LedgerEventType.DECISION_MADE,
            LedgerEventType.APPROVAL_CREATED);
  }

  @Test
  void deniesAnUnknownPayeeAndPersistsTheReason() throws Exception {
    CreatedAgent agent = newAgent(100_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-3", "mallory@example.com", 90_000, "USD", "Invoice inv-3", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("DENIED"))
        .andExpect(jsonPath("$.decision.outcome").value("DENY"))
        .andExpect(jsonPath("$.decision.reasons.length()").value(1))
        .andExpect(jsonPath("$.decision.reasons[0].code").value("PAYEE_NOT_ALLOWED"))
        .andExpect(
            jsonPath("$.decision.reasons[0].message").value("Payee is not on the allow-list"))
        .andExpect(jsonPath("$.budget.spentMinor").value(0))
        .andExpect(jsonPath("$.budget.reservedMinor").value(0))
        .andExpect(jsonPath("$.budget.remainingMinor").value(100_000));

    UUID paymentId = singlePaymentId();
    assertThat(paymentState(paymentId)).isEqualTo("DENIED");
    assertThat(primaryReasonCode(paymentId)).isEqualTo("PAYEE_NOT_ALLOWED");
    assertThat(paypal.submissions()).isEmpty();
    assertThat(eventTypes(paymentId))
        .containsExactly(LedgerEventType.PAYMENT_REQUESTED, LedgerEventType.DECISION_MADE);
  }

  @Test
  void deniesWhenTheWeeklyBudgetWouldBeExceeded() throws Exception {
    CreatedAgent agent = newAgent(5_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-4", "alice@example.com", 6_000, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("DENIED"))
        .andExpect(jsonPath("$.decision.reasons.length()").value(1))
        .andExpect(jsonPath("$.decision.reasons[0].code").value("BUDGET_EXCEEDED"))
        .andExpect(jsonPath("$.budget.spentMinor").value(0))
        .andExpect(jsonPath("$.budget.remainingMinor").value(5_000));

    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void deniesAnUnsupportedCurrencyInsteadOfFailingAtTheAdapter() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-5", "alice@example.com", 1_200, "EUR", "Invoice inv-5", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("DENIED"))
        .andExpect(jsonPath("$.decision.reasons[0].code").value("CURRENCY_NOT_SUPPORTED"));

    UUID paymentId = singlePaymentId();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT currency FROM payment_requests WHERE id = ?", String.class, paymentId))
        .isEqualTo("EUR");
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void reportsEveryViolationInTheFixedRuleOrder() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-6", "alice@example.com", 100_001, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("DENIED"))
        .andExpect(jsonPath("$.decision.reasons.length()").value(2))
        .andExpect(jsonPath("$.decision.reasons[0].code").value("AMOUNT_EXCEEDS_CAP"))
        .andExpect(jsonPath("$.decision.reasons[1].code").value("BUDGET_EXCEEDED"));

    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void replaysAnIdenticalRequestWithoutCallingPayPalAgain() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    MockHttpServletRequestBuilder request =
        payment("invoice-7", "alice@example.com", 1_200, "USD", "Invoice inv-7", agent);

    MvcResult first = mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    MvcResult second =
        mockMvc
            .perform(
                payment("invoice-7", "alice@example.com", 1_200, "USD", "Invoice inv-7", agent))
            .andExpect(status().isCreated())
            .andExpect(header().string(IDEMPOTENT_REPLAYED, "true"))
            .andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(paymentCount()).isEqualTo(1);
    assertThat(paypal.submissions()).hasSize(1);
    assertThat(eventTypes(singlePaymentId()))
        .containsExactly(
            LedgerEventType.PAYMENT_REQUESTED,
            LedgerEventType.DECISION_MADE,
            LedgerEventType.PAYPAL_SUBMITTED);
  }

  @Test
  void rejectsTheSameKeyWithADifferentBody() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("invoice-8", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated());

    mockMvc
        .perform(payment("invoice-8", "alice@example.com", 1_300, "USD", "", agent))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.status").value(422));

    assertThat(paymentCount()).isEqualTo(1);
    assertThat(paypal.submissions()).hasSize(1);
  }

  @Test
  void requiresTheIdempotencyKeyHeader() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("alice@example.com", 1_200, "USD", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

    assertThat(paymentCount()).isZero();
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void rejectsAMalformedIdempotencyKey() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(payment("bad key!", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

    assertThat(paymentCount()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void rejectsInvalidBodies(String description, Map<String, Object> body) throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(
            post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
                .header("Idempotency-Key", "invalid-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details").isArray());

    assertThat(paymentCount()).isZero();
    assertThat(paypal.submissions()).isEmpty();
  }

  @ParameterizedTest(name = "httpStatus={0}")
  @CsvSource({"500", "503", "429"})
  void leavesAPaymentApprovedForTheReconcilerAfterATransientFailure(int httpStatus)
      throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.failNextSubmission(
        new PayPalPayoutException("PayPal is unavailable", httpStatus, "debug-transient"));

    mockMvc
        .perform(payment("invoice-transient", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("APPROVED"))
        .andExpect(jsonPath("$.decision.outcome").value("APPROVE"))
        .andExpect(jsonPath("$.budget.spentMinor").value(1_200))
        .andExpect(jsonPath("$.budget.remainingMinor").value(48_800));

    UUID paymentId = singlePaymentId();
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT paypal_batch_id, raw_status, version FROM payment_requests WHERE id = ?",
            paymentId);
    assertThat(row.get("paypal_batch_id")).isNull();
    assertThat(row.get("raw_status")).isNull();
    assertThat(row.get("version")).isEqualTo(0L);
    assertThat(paypal.submissions()).isEmpty();
    assertThat(eventTypes(paymentId))
        .containsExactly(LedgerEventType.PAYMENT_REQUESTED, LedgerEventType.DECISION_MADE);
  }

  @Test
  void treatsATimeoutWithoutAnHttpStatusAsTransient() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.failNextSubmission(new PayPalPayoutException("Read timed out"));

    mockMvc
        .perform(payment("invoice-timeout", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("APPROVED"));

    assertThat(paymentState(singlePaymentId())).isEqualTo("APPROVED");
  }

  @ParameterizedTest(name = "httpStatus={0}")
  @CsvSource({"400", "404", "422"})
  void marksAPaymentFailedAfterADefinitiveRejectionAndReleasesTheBudget(int httpStatus)
      throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.failNextSubmission(
        new PayPalPayoutException("PayPal rejected the payout", httpStatus, "debug-permanent"));

    mockMvc
        .perform(payment("invoice-permanent", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("FAILED"))
        // The response budget is the decision-time snapshot (stored for idempotent replay); the
        // live aggregate below proves the failed payment released its amount.
        .andExpect(jsonPath("$.budget.spentMinor").value(1_200))
        .andExpect(jsonPath("$.budget.remainingMinor").value(48_800));

    UUID paymentId = singlePaymentId();
    assertThat(paymentState(paymentId)).isEqualTo("FAILED");
    assertThat(committedMinor()).isZero();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT raw_status, version FROM payment_requests WHERE id = ?", paymentId);
    assertThat(row.get("raw_status")).isEqualTo("SUBMISSION_FAILED");
    assertThat(row.get("version")).isEqualTo(1L);
    assertThat(paypal.submissions()).isEmpty();

    LedgerEvent failure = eventOfType(paymentId, LedgerEventType.PAYPAL_STATUS_CHANGED);
    assertThat(failure.actor()).isEqualTo("paypal-adapter");
    assertThat(failure.payload())
        .containsEntry("state", "FAILED")
        .containsEntry("reason", "SUBMISSION_FAILED")
        .containsEntry("httpStatus", httpStatus);

    paypal.clearFailure();
    mockMvc
        .perform(payment("invoice-after-failure", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("SUBMITTED"));
  }

  @Test
  void leavesAPaymentApprovedWhenPayPalCredentialsAreMissing() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.failNextSubmission(
        new PayPalOAuthException(
            "PayPal client credentials are not configured. Set PAYPAL_CLIENT_ID and "
                + "PAYPAL_CLIENT_SECRET."));

    mockMvc
        .perform(payment("invoice-no-credentials", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("APPROVED"))
        .andExpect(jsonPath("$.decision.outcome").value("APPROVE"))
        .andExpect(jsonPath("$.budget.spentMinor").value(1_200))
        .andExpect(jsonPath("$.budget.remainingMinor").value(48_800));

    UUID paymentId = singlePaymentId();
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    assertThat(paymentCount()).isEqualTo(1);
    assertThat(paypal.submissions()).isEmpty();
    assertThat(eventTypes(paymentId))
        .containsExactly(LedgerEventType.PAYMENT_REQUESTED, LedgerEventType.DECISION_MADE);
  }

  @Test
  void commitsTheDecisionBeforeContactingPayPal() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.blockSubmissions();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CompletableFuture<MvcResult> future =
          performAsync(
              executor, payment("invoice-crash", "alice@example.com", 1_200, "USD", "", agent));

      assertThat(paypal.awaitSubmitEntered(Duration.ofSeconds(10))).isTrue();

      UUID paymentId = singlePaymentId();
      assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
      Map<String, Object> row =
          jdbcTemplate.queryForMap(
              "SELECT paypal_batch_id, version FROM payment_requests WHERE id = ?", paymentId);
      assertThat(row.get("paypal_batch_id")).isNull();
      assertThat(row.get("version")).isEqualTo(0L);
      assertThat(eventTypes(paymentId))
          .containsExactly(LedgerEventType.PAYMENT_REQUESTED, LedgerEventType.DECISION_MADE);

      paypal.releaseSubmissions();
      MvcResult result = future.get(15, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus()).isEqualTo(201);
      assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    } finally {
      paypal.releaseSubmissions();
      executor.shutdownNow();
    }
  }

  @Test
  void twoConcurrentRequestsWithTheSameKeyCreateOnePaymentAndOnePayout() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.blockSubmissions();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CompletableFuture<MvcResult> first =
          performAsync(
              executor, payment("invoice-race", "alice@example.com", 1_200, "USD", "", agent));
      CompletableFuture<MvcResult> second =
          performAsync(
              executor, payment("invoice-race", "alice@example.com", 1_200, "USD", "", agent));

      assertThat(paypal.awaitSubmitEntered(Duration.ofSeconds(10))).isTrue();
      paypal.releaseSubmissions();

      MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
      MvcResult secondResult = second.get(15, TimeUnit.SECONDS);

      assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
      assertThat(paymentCount()).isEqualTo(1);
      assertThat(paypal.submissions()).hasSize(1);

      UUID paymentId = singlePaymentId();
      assertThat(firstResult.getResponse().getContentAsString()).contains(paymentId.toString());
      assertThat(secondResult.getResponse().getContentAsString()).contains(paymentId.toString());

      long replays =
          Stream.of(firstResult, secondResult)
              .filter(result -> "true".equals(result.getResponse().getHeader(IDEMPOTENT_REPLAYED)))
              .count();
      assertThat(replays).isEqualTo(1);
    } finally {
      paypal.releaseSubmissions();
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentRequestsNeverExceedTheWeeklyBudget() throws Exception {
    CreatedAgent agent = newAgent(5_000, 10_000, "alice@example.com");
    int attempts = 5;
    ExecutorService executor = Executors.newFixedThreadPool(attempts);
    try {
      List<CompletableFuture<MvcResult>> futures = new ArrayList<>();
      for (int index = 0; index < attempts; index++) {
        futures.add(
            performAsync(
                executor,
                payment("invoice-race-" + index, "alice@example.com", 2_000, "USD", "", agent)));
      }

      List<MvcResult> results = new ArrayList<>();
      for (CompletableFuture<MvcResult> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }

      assertThat(results)
          .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
      assertThat(paymentCount()).isEqualTo(attempts);
      assertThat(paypal.submissions()).hasSize(2);

      long committed = committedMinor();
      assertThat(committed).isEqualTo(4_000L);
      assertThat(committed).isLessThanOrEqualTo(5_000L);

      long denied =
          results.stream()
              .filter(result -> {
                try {
                  return result
                      .getResponse()
                      .getContentAsString(StandardCharsets.UTF_8)
                      .contains("BUDGET_EXCEEDED");
                } catch (Exception exception) {
                  throw new CompletionException(exception);
                }
              })
              .count();
      assertThat(denied).isEqualTo(3);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void aSuspendedAgentCannotCreateAPayment() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    jdbcTemplate.update("UPDATE agents SET status = 'SUSPENDED' WHERE id = ?", agent.agent().id());

    mockMvc
        .perform(payment("invoice-suspended", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AGENT_SUSPENDED"));

    assertThat(paymentCount()).isZero();
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void neverWritesTheAgentApiKeyToTheLedgerOrTheResponse() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    MvcResult result =
        mockMvc
            .perform(payment("invoice-secret", "alice@example.com", 1_200, "USD", "", agent))
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain(agent.apiKey());
    Long leaks =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_events WHERE payment_id = ?"
                + " AND strpos(payload::text, ?) > 0",
            Long.class,
            singlePaymentId(),
            agent.apiKey());
    assertThat(leaks).isZero();
  }

  @Test
  void returnsAnOwnedPaymentToItsAgent() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    mockMvc
        .perform(payment("invoice-read", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated());

    UUID paymentId = singlePaymentId();
    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.state").value("SUBMITTED"))
        .andExpect(jsonPath("$.decision.outcome").value("APPROVE"))
        .andExpect(jsonPath("$.decision.reasons").isEmpty())
        .andExpect(jsonPath("$.budget.limitMinor").value(50_000))
        .andExpect(jsonPath("$.budget.spentMinor").value(1_200))
        .andExpect(jsonPath("$.budget.remainingMinor").value(48_800));
  }

  @Test
  void hidesAnotherAgentsPaymentBehindTheSame404() throws Exception {
    CreatedAgent owner = newAgent(50_000, 10_000, "alice@example.com");
    CreatedAgent intruder = newAgent(50_000, 10_000, "alice@example.com");
    mockMvc
        .perform(payment("invoice-owned", "alice@example.com", 1_200, "USD", "", owner))
        .andExpect(status().isCreated());
    UUID paymentId = singlePaymentId();

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder.apiKey()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", paymentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
  }

  @Test
  void returns404ForAnUnknownPayment() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(
            get("/api/v1/payments/{paymentId}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
  }

  @Test
  void requiresAnApiKeyToReadAPaymentOrBudget() throws Exception {
    mockMvc
        .perform(get("/api/v1/payments/{paymentId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/budget")).andExpect(status().isUnauthorized());
  }

  @Test
  void reportsLiveSpentReservedAndRemainingBudget() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(50_000))
        .andExpect(jsonPath("$.spentMinor").value(0))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(50_000));

    mockMvc
        .perform(payment("invoice-budget-approve", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("SUBMITTED"));
    mockMvc
        .perform(payment("invoice-budget-escalate", "alice@example.com", 15_000, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"));
    mockMvc
        .perform(payment("invoice-budget-deny", "mallory@example.com", 90_000, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("DENIED"));

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(50_000))
        .andExpect(jsonPath("$.spentMinor").value(1_200))
        .andExpect(jsonPath("$.reservedMinor").value(15_000))
        .andExpect(jsonPath("$.remainingMinor").value(33_800));
  }

  @Test
  void releasesBudgetWhenASubmissionFailsPermanently() throws Exception {
    CreatedAgent agent = newAgent(50_000, 10_000, "alice@example.com");
    paypal.failNextSubmission(new PayPalPayoutException("PayPal rejected it", 422, "debug-budget"));

    mockMvc
        .perform(payment("invoice-budget-failed", "alice@example.com", 1_200, "USD", "", agent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("FAILED"));

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spentMinor").value(0))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(50_000));
  }

  private CreatedAgent newAgent(long weeklyLimitMinor, long thresholdMinor, String... payees) {
    return agentService.createAgent(
        "Test Agent", weeklyLimitMinor, thresholdMinor, List.of(payees));
  }

  private MockHttpServletRequestBuilder payment(
      String idempotencyKey,
      String payeeEmail,
      long amountMinor,
      String currency,
      String memo,
      CreatedAgent agent) {
    return post("/api/v1/payments")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(requestBody(payeeEmail, amountMinor, currency, memo));
  }

  private String requestBody(String payeeEmail, long amountMinor, String currency, String memo) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payeeEmail", payeeEmail);
    body.put("amountMinor", amountMinor);
    body.put("currency", currency);
    body.put("memo", memo);
    return objectMapper.writeValueAsString(body);
  }

  private CompletableFuture<MvcResult> performAsync(
      ExecutorService executor, MockHttpServletRequestBuilder request) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return mockMvc.perform(request).andReturn();
          } catch (Exception exception) {
            throw new CompletionException(exception);
          }
        },
        executor);
  }

  private long paymentCount() {
    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_requests", Long.class);
    return count == null ? 0 : count;
  }

  private long committedMinor() {
    Long committed =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_minor), 0) FROM payment_requests"
                + " WHERE state IN ('APPROVED', 'SUBMITTED', 'SUCCEEDED')",
            Long.class);
    return committed == null ? 0 : committed;
  }

  private UUID singlePaymentId() {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM payment_requests ORDER BY created_at LIMIT 1", UUID.class);
  }

  private String paymentState(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private String primaryReasonCode(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT decision_reasons -> 0 ->> 'code' FROM payment_requests WHERE id = ?",
        String.class,
        paymentId);
  }

  private List<LedgerEventType> eventTypes(UUID paymentId) {
    return ledgerService.eventsForPayment(paymentId).stream().map(LedgerEvent::eventType).toList();
  }

  private String eventActor(UUID paymentId, LedgerEventType type) {
    return eventOfType(paymentId, type).actor();
  }

  private LedgerEvent eventOfType(UUID paymentId, LedgerEventType type) {
    return ledgerService.eventsForPayment(paymentId).stream()
        .filter(event -> event.eventType() == type)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing ledger event " + type));
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(
        Arguments.of("zero amount", body("alice@example.com", 0, "USD", "")),
        Arguments.of("negative amount", body("alice@example.com", -1, "USD", "")),
        Arguments.of("short currency", body("alice@example.com", 1_200, "US", "")),
        Arguments.of("missing payee", body(null, 1_200, "USD", "")),
        Arguments.of("malformed payee", body("not-an-email", 1_200, "USD", "")));
  }

  private static Map<String, Object> body(
      String payeeEmail, long amountMinor, String currency, String memo) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (payeeEmail != null) {
      body.put("payeeEmail", payeeEmail);
    }
    body.put("amountMinor", amountMinor);
    body.put("currency", currency);
    body.put("memo", memo);
    return body;
  }
}
