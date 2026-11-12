package com.leash.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.common.money.SupportedCurrency;
import com.leash.paypal.FakePayPalPayoutsClient;
import com.leash.paypal.PayPalPayoutException;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the Slice 3.2 approval workflow end to end through the admin chain.
 *
 * <p>Every decision goes through HTTP Basic plus the cookie-backed CSRF token. The tests cover the
 * happy path (approve submits, reject releases), idempotent repeats, an approve-versus-reject race
 * with exactly one winner, the full hard-rule re-check, agent-key isolation, CSRF rejection,
 * validation, unknown ids, and a transient PayPal failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminApprovalApiIntegrationTest.TestConfig.class})
class AdminApprovalApiIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "admin-test-password";
  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;
  @Autowired private FakePayPalPayoutsClient paypal;
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
  void approveSubmitsThePaymentAndLedgersTheDecision() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/approvals")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].approvalId").value(approvalId.toString()))
        .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$[0].agentId").value(agent.agent().id().toString()))
        .andExpect(jsonPath("$[0].status").value("PENDING"))
        .andExpect(jsonPath("$[0].paymentState").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$[0].payeeEmail").value("alice@example.com"))
        .andExpect(jsonPath("$[0].amountMinor").value(15_000))
        .andExpect(jsonPath("$[0].currency").value("USD"))
        .andExpect(jsonPath("$[0].memo").value("Server invoice"));

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("Approved for the demo")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(approvalId.toString()))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.paymentState").value("SUBMITTED"))
        .andExpect(jsonPath("$.decidedBy").value(ADMIN_USERNAME))
        .andExpect(jsonPath("$.note").value("Approved for the demo"))
        .andExpect(jsonPath("$.replayed").value(false))
        .andExpect(jsonPath("$.autoRejected").value(false))
        .andExpect(jsonPath("$.rejectionReasons").isEmpty());

    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(paypal.submissions()).hasSize(1);
    assertThat(
            paypal.requestFor(paypal.submissions().getFirst().payoutBatchId()).amount().currency())
        .isEqualTo(SupportedCurrency.USD);

    Map<String, Object> decided = approvalRow(approvalId);
    assertThat(decided.get("status")).isEqualTo("APPROVED");
    assertThat(decided.get("decided_by")).isEqualTo(ADMIN_USERNAME);
    assertThat(decided.get("note")).isEqualTo("Approved for the demo");

    assertThat(eventTypes(paymentId))
        .containsExactly(
            "PAYMENT_REQUESTED",
            "DECISION_MADE",
            "APPROVAL_CREATED",
            "APPROVAL_DECIDED",
            "PAYPAL_SUBMITTED");
    Map<String, Object> approvalEvent = latestLedgerEvent(paymentId, "APPROVAL_DECIDED");
    assertThat(approvalEvent.get("actor")).isEqualTo(ADMIN_USERNAME);
    JsonNode payload = objectMapper.readTree((String) approvalEvent.get("payload"));
    assertThat(payload.get("approvalId").asText()).isEqualTo(approvalId.toString());
    assertThat(payload.get("decision").asText()).isEqualTo("APPROVED");
    assertThat(payload.get("reason").asText()).isEqualTo("HUMAN_APPROVAL");
    assertThat(payload.get("previousState").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(payload.get("state").asText()).isEqualTo("APPROVED");
    assertThat(payload.get("note").asText()).isEqualTo("Approved for the demo");

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(50_000))
        .andExpect(jsonPath("$.spentMinor").value(15_000))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(35_000));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/approvals")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void aSecondApproveIsANoOpAndNeverCallsPayPalAgain() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    assertThat(decide("approve", approvalId, csrf, "first")).isEqualTo(200);
    assertThat(paypal.submissions()).hasSize(1);
    assertThat(ledgerCount(paymentId, "APPROVAL_DECIDED")).isEqualTo(1);

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("second")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.paymentState").value("SUBMITTED"))
        .andExpect(jsonPath("$.replayed").value(true))
        .andExpect(jsonPath("$.note").value("first"));

    assertThat(paypal.submissions()).hasSize(1);
    assertThat(ledgerCount(paymentId, "APPROVAL_DECIDED")).isEqualTo(1);
  }

  @Test
  void rejectReleasesTheReservationAndBlocksASubsequentApprove() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(rejectPath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("Not this week")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.paymentState").value("REJECTED"))
        .andExpect(jsonPath("$.decidedBy").value(ADMIN_USERNAME))
        .andExpect(jsonPath("$.note").value("Not this week"))
        .andExpect(jsonPath("$.replayed").value(false))
        .andExpect(jsonPath("$.autoRejected").value(false));

    assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
    assertThat(paypal.submissions()).isEmpty();

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spentMinor").value(0))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(50_000));

    Map<String, Object> approvalEvent = latestLedgerEvent(paymentId, "APPROVAL_DECIDED");
    JsonNode payload = objectMapper.readTree((String) approvalEvent.get("payload"));
    assertThat(payload.get("decision").asText()).isEqualTo("REJECTED");
    assertThat(payload.get("reason").asText()).isEqualTo("HUMAN_REJECTION");
    assertThat(payload.get("state").asText()).isEqualTo("REJECTED");

    // The same decision is a no-op; the opposite decision is a conflict.
    assertThat(decide("reject", approvalId, csrf, "again")).isEqualTo(200);
    assertThat(ledgerCount(paymentId, "APPROVAL_DECIDED")).isEqualTo(1);

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("too late")),
                csrf))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("APPROVAL_ALREADY_DECIDED"))
        .andExpect(jsonPath("$.status").value(409));

    assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void approveRacingRejectHasExactlyOneWinner() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      Callable<Integer> approve =
          () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return decide("approve", approvalId, csrf, "race");
          };
      Callable<Integer> reject =
          () -> {
            ready.countDown();
            go.await(5, TimeUnit.SECONDS);
            return decide("reject", approvalId, csrf, "race");
          };

      Future<Integer> approveResult = executor.submit(approve);
      Future<Integer> rejectResult = executor.submit(reject);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      List<Integer> statuses =
          List.of(approveResult.get(20, TimeUnit.SECONDS), rejectResult.get(20, TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    } finally {
      executor.shutdownNow();
    }

    String decidedStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM approvals WHERE id = ?", String.class, approvalId);
    assertThat(ledgerCount(paymentId, "APPROVAL_DECIDED")).isEqualTo(1);

    if ("APPROVED".equals(decidedStatus)) {
      assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
      assertThat(paypal.submissions()).hasSize(1);
    } else {
      assertThat(decidedStatus).isEqualTo("REJECTED");
      assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
      assertThat(paypal.submissions()).isEmpty();
    }
  }

  @Test
  void approveAutoRejectsWhenTheAgentWasSuspended() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    agentService.suspendAgent(agent.agent().id(), ADMIN_USERNAME);
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("should not pass")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.paymentState").value("REJECTED"))
        .andExpect(jsonPath("$.autoRejected").value(true))
        .andExpect(jsonPath("$.rejectionReasons.length()").value(1))
        .andExpect(jsonPath("$.rejectionReasons[0].code").value("AGENT_SUSPENDED"));

    assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
    assertThat(paypal.submissions()).isEmpty();
    assertThat(reservedMinor()).isZero();

    JsonNode payload =
        objectMapper.readTree(
            (String) latestLedgerEvent(paymentId, "APPROVAL_DECIDED").get("payload"));
    assertThat(payload.get("decision").asText()).isEqualTo("REJECTED");
    assertThat(payload.get("reason").asText()).isEqualTo("HARD_RULE_VIOLATION");
    assertThat(payload.get("policyReasons").get(0).get("code").asText())
        .isEqualTo("AGENT_SUSPENDED");
  }

  @Test
  void approveAutoRejectsWhenThePayeeWasRemovedFromThePolicy() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    agentService.updatePolicy(
        agent.agent().id(), 50_000, 10_000, List.of("bob@example.com"), ADMIN_USERNAME);
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("payee check")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.autoRejected").value(true))
        .andExpect(jsonPath("$.rejectionReasons[0].code").value("PAYEE_NOT_ALLOWED"));

    assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void approveAutoRejectsWhenTheWeeklyLimitWasLoweredBelowThePayment() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    agentService.updatePolicy(
        agent.agent().id(), 10_000, 10_000, List.of("alice@example.com"), ADMIN_USERNAME);
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("budget check")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.autoRejected").value(true))
        .andExpect(jsonPath("$.rejectionReasons[0].code").value("BUDGET_EXCEEDED"));

    assertThat(paymentState(paymentId)).isEqualTo("REJECTED");
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void anApprovalDecidedBeforeASubmissionFailureLeavesThePaymentApproved() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    paypal.failNextSubmission(
        new PayPalPayoutException("PayPal is unavailable", 503, "debug-approval"));
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("transient")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.paymentState").value("APPROVED"))
        .andExpect(jsonPath("$.replayed").value(false));

    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    assertThat(paypal.submissions()).isEmpty();
    assertThat(ledgerCount(paymentId, "PAYPAL_SUBMITTED")).isZero();
  }

  @Test
  void unknownApprovalIdsReturnApprovalNotFound() throws Exception {
    String csrf = csrfToken();
    UUID missing = UUID.randomUUID();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(missing)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
                csrf))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404));

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(rejectPath(missing)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
                csrf))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("APPROVAL_NOT_FOUND"));
  }

  @Test
  void anAgentKeyCannotReachTheApprovalQueue() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    List<MockHttpServletRequestBuilder> requests =
        List.of(
            get("/api/v1/admin/approvals"),
            post(approvePath(approvalId)).contentType(MediaType.APPLICATION_JSON).content("{}"),
            post(rejectPath(approvalId)).contentType(MediaType.APPLICATION_JSON).content("{}"));

    for (MockHttpServletRequestBuilder request : requests) {
      mockMvc
          .perform(
              withCsrf(request, csrf).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
  }

  @Test
  void anApprovalDecisionWithoutCsrfIsRejected() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);

    mockMvc
        .perform(
            mockAdmin(post(approvePath(approvalId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));

    assertThat(paymentState(paymentId)).isEqualTo("PENDING_APPROVAL");
    assertThat(paypal.submissions()).isEmpty();
  }

  @Test
  void anOversizedNoteIsRejectedByValidation() throws Exception {
    CreatedAgent agent = createAgent();
    UUID paymentId = escalate(agent, 15_000, "Server invoice");
    UUID approvalId = approvalIdFor(paymentId);
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(approvePath(approvalId)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody("x".repeat(501))),
                csrf))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details").isArray());

    assertThat(paymentState(paymentId)).isEqualTo("PENDING_APPROVAL");
    assertThat(paypal.submissions()).isEmpty();
  }

  private CreatedAgent createAgent() {
    return agentService.createAgent(
        "Approval Agent", 50_000L, 10_000L, List.of("alice@example.com"));
  }

  private UUID escalate(CreatedAgent agent, long amountMinor, String memo) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/payments")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey())
                    .header("Idempotency-Key", "invoice-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "payeeEmail",
                                "alice@example.com",
                                "amountMinor",
                                amountMinor,
                                "currency",
                                "USD",
                                "memo",
                                memo))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.decision.outcome").value("ESCALATE"))
            .andExpect(jsonPath("$.budget.reservedMinor").value(amountMinor))
            .andReturn();
    return UUID.fromString(readJson(result).get("paymentId").asText());
  }

  private int decide(String decision, UUID approvalId, String csrf, String note) throws Exception {
    String path =
        "/api/v1/admin/approvals/"
            + approvalId
            + ("approve".equals(decision) ? "/approve" : "/reject");
    return mockMvc
        .perform(
            withCsrf(
                mockAdmin(post(path))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(noteBody(note)),
                csrf))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String csrfToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/approvals")))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
    assertThat(cookie).as("XSRF-TOKEN cookie").isNotNull();
    return cookie.getValue();
  }

  private static MockHttpServletRequestBuilder mockAdmin(MockHttpServletRequestBuilder builder) {
    return builder.with(httpBasic(ADMIN_USERNAME, ADMIN_PASSWORD));
  }

  private static MockHttpServletRequestBuilder withCsrf(
      MockHttpServletRequestBuilder builder, String token) {
    return builder.cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token);
  }

  private static String approvePath(UUID approvalId) {
    return "/api/v1/admin/approvals/" + approvalId + "/approve";
  }

  private static String rejectPath(UUID approvalId) {
    return "/api/v1/admin/approvals/" + approvalId + "/reject";
  }

  private String noteBody(String note) {
    return note == null ? "{}" : objectMapper.writeValueAsString(Map.of("note", note));
  }

  private UUID approvalIdFor(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM approvals WHERE payment_id = ?", UUID.class, paymentId);
  }

  private Map<String, Object> approvalRow(UUID approvalId) {
    return jdbcTemplate.queryForMap(
        "SELECT status, decided_by, note FROM approvals WHERE id = ?", approvalId);
  }

  private String paymentState(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private long reservedMinor() {
    Long reserved =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_minor), 0) FROM payment_requests"
                + " WHERE state = 'PENDING_APPROVAL'",
            Long.class);
    return reserved == null ? 0 : reserved;
  }

  private List<String> eventTypes(UUID paymentId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM ledger_events WHERE payment_id = ? ORDER BY id",
        String.class,
        paymentId);
  }

  private Map<String, Object> latestLedgerEvent(UUID paymentId, String eventType) {
    return jdbcTemplate.queryForMap(
        "SELECT actor, payload::text AS payload FROM ledger_events"
            + " WHERE payment_id = ? AND event_type = ? ORDER BY id DESC LIMIT 1",
        paymentId,
        eventType);
  }

  private long ledgerCount(UUID paymentId, String eventType) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_events WHERE payment_id = ? AND event_type = ?",
            Long.class,
            paymentId,
            eventType);
    return count == null ? 0 : count;
  }

  private JsonNode readJson(MvcResult result) {
    try {
      return readJson(result.getResponse().getContentAsString());
    } catch (Exception exception) {
      throw new IllegalStateException("Response is not valid JSON", exception);
    }
  }

  private JsonNode readJson(String json) {
    return objectMapper.readTree(json);
  }
}
