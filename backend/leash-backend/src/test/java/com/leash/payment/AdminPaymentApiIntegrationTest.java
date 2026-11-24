package com.leash.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.common.MutableClock;
import com.leash.paypal.FakePayPalPayoutsClient;
import com.leash.paypal.PayPalPayoutException;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the Slice 3.3 admin payment read side end to end.
 *
 * <p>The list is paged, newest first, filtered by status, agent, and a half-open time window, and
 * joined with the agent name. A counting {@link JdbcTemplate} asserts the list stays at exactly two
 * statements (page plus count) no matter the page size or filters. The timeline is the append-only
 * ledger, and manual refresh applies a PayPal poll through the reconciler path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminPaymentApiIntegrationTest.TestConfig.class})
class AdminPaymentApiIntegrationTest {

  private static final Instant START = Instant.parse("2026-11-11T12:00:00Z");
  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "admin-test-password";
  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final String ALICE = "alice@example.com";

  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CountingJdbcTemplate countingJdbcTemplate;
  @Autowired private MockMvc mockMvc;
  @Autowired private FakePayPalPayoutsClient paypal;
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

    @Bean
    @Primary
    CountingJdbcTemplate countingJdbcTemplate(DataSource dataSource) {
      return new CountingJdbcTemplate(dataSource);
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
    clock.setInstant(START);
  }

  @Test
  void listsPaymentsNewestFirstWithAgentNamesAndThePageEnvelope() throws Exception {
    CreatedAgent alpha = createAgent("Alpha Agent");
    CreatedAgent beta = createAgent("Beta Agent");

    UUID oldest = createPayment(alpha, 1_200, ALICE, "First invoice");
    clock.advance(Duration.ofMinutes(1));
    UUID middle = createPayment(beta, 90_000, "mallory@example.com", "Unknown payee");
    clock.advance(Duration.ofMinutes(1));
    UUID newest = createPayment(alpha, 15_000, ALICE, "Server invoice");

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("page", "0").param("size", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].paymentId").value(newest.toString()))
        .andExpect(jsonPath("$.content[0].agentId").value(alpha.agent().id().toString()))
        .andExpect(jsonPath("$.content[0].agentName").value("Alpha Agent"))
        .andExpect(jsonPath("$.content[0].payeeEmail").value(ALICE))
        .andExpect(jsonPath("$.content[0].amountMinor").value(15_000))
        .andExpect(jsonPath("$.content[0].currency").value("USD"))
        .andExpect(jsonPath("$.content[0].memo").value("Server invoice"))
        .andExpect(jsonPath("$.content[0].state").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.content[0].decisionOutcome").value("ESCALATE"))
        .andExpect(jsonPath("$.content[0].decisionReasons").isEmpty())
        .andExpect(jsonPath("$.content[1].paymentId").value(middle.toString()))
        .andExpect(jsonPath("$.content[1].agentName").value("Beta Agent"))
        .andExpect(jsonPath("$.content[1].state").value("DENIED"))
        .andExpect(jsonPath("$.content[1].decisionOutcome").value("DENY"))
        .andExpect(jsonPath("$.content[1].decisionReasons[0].code").value("PAYEE_NOT_ALLOWED"));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("page", "1").param("size", "2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].paymentId").value(oldest.toString()))
        .andExpect(jsonPath("$.content[0].state").value("SUBMITTED"));
  }

  @Test
  void filtersByStatusAgentAndATimeWindow() throws Exception {
    CreatedAgent alpha = createAgent("Alpha Agent");
    CreatedAgent beta = createAgent("Beta Agent");

    UUID first = createPayment(alpha, 1_200, ALICE, "First");
    clock.advance(Duration.ofMinutes(1));
    UUID second = createPayment(beta, 90_000, "mallory@example.com", "Second");
    clock.advance(Duration.ofMinutes(1));
    UUID third = createPayment(alpha, 15_000, ALICE, "Third");

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("status", "DENIED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].paymentId").value(second.toString()));

    mockMvc
        .perform(
            mockAdmin(
                get("/api/v1/admin/payments").param("agentId", alpha.agent().id().toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].paymentId").value(third.toString()))
        .andExpect(jsonPath("$.content[1].paymentId").value(first.toString()));

    mockMvc
        .perform(
            mockAdmin(
                get("/api/v1/admin/payments")
                    .param("from", START.plus(Duration.ofSeconds(30)).toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].paymentId").value(third.toString()))
        .andExpect(jsonPath("$.content[1].paymentId").value(second.toString()));

    mockMvc
        .perform(
            mockAdmin(
                get("/api/v1/admin/payments")
                    .param("to", START.plus(Duration.ofSeconds(30)).toString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].paymentId").value(first.toString()));

    mockMvc
        .perform(
            mockAdmin(
                get("/api/v1/admin/payments")
                    .param("agentId", beta.agent().id().toString())
                    .param("status", "DENIED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].paymentId").value(second.toString()));
  }

  @Test
  void rejectsOutOfRangePagingAndAnInvertedTimeWindow() throws Exception {
    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("size", "101")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
        .andExpect(jsonPath("$.detail").value(containsString("1 and 100")));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("size", "0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("page", "-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"));

    mockMvc
        .perform(
            mockAdmin(
                get("/api/v1/admin/payments")
                    .param("from", START.plus(Duration.ofMinutes(5)).toString())
                    .param("to", START.toString())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
        .andExpect(jsonPath("$.detail").value("from must be before to"));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments").param("size", "100")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void theListUsesExactlyTwoQueriesRegardlessOfPageSize() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    for (int index = 0; index < 5; index++) {
      createPayment(agent, 1_200, ALICE, "Invoice " + index);
      clock.advance(Duration.ofSeconds(1));
    }

    assertQueryCount("/api/v1/admin/payments?page=0&size=1", 2);
    assertQueryCount("/api/v1/admin/payments?page=0&size=5", 2);
    assertQueryCount("/api/v1/admin/payments?page=1&size=2", 2);
    assertQueryCount("/api/v1/admin/payments?size=3&agentId=" + agent.agent().id(), 2);
  }

  @Test
  void returnsTheLedgerTimelineInOrderForOnePayment() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    UUID paymentId = createPayment(agent, 1_200, ALICE, "Timeline invoice");

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments/" + paymentId + "/events")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].eventType").value("PAYMENT_REQUESTED"))
        .andExpect(jsonPath("$[0].actor").value("agent"))
        .andExpect(jsonPath("$[0].payload.payeeEmail").value(ALICE))
        .andExpect(jsonPath("$[0].payload.amountMinor").value(1_200))
        .andExpect(jsonPath("$[0].payload.currency").value("USD"))
        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[1].eventType").value("DECISION_MADE"))
        .andExpect(jsonPath("$[1].actor").value("policy-engine"))
        .andExpect(jsonPath("$[1].payload.outcome").value("APPROVE"))
        .andExpect(jsonPath("$[2].eventType").value("PAYPAL_SUBMITTED"))
        .andExpect(jsonPath("$[2].actor").value("paypal-adapter"));

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments/" + UUID.randomUUID() + "/events")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
  }

  @Test
  void refreshPollsPayPalAppliesTheStatusAndThenBecomesANoOp() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    UUID paymentId = createPayment(agent, 1_200, ALICE, "Refresh invoice");
    String batchId = paypalBatchId(paymentId);
    paypal.setStatus(batchId, "SUCCESS", "SUCCESS");
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post(refreshPath(paymentId))), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.paypalBatchId").value(batchId))
        .andExpect(jsonPath("$.rawStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.refreshed").value(true))
        .andExpect(jsonPath("$.reason").doesNotExist());

    assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED");
    assertThat(eventTypes(paymentId))
        .containsExactly("PAYMENT_REQUESTED", "DECISION_MADE", "PAYPAL_SUBMITTED", "RECONCILED");

    mockMvc
        .perform(withCsrf(mockAdmin(post(refreshPath(paymentId))), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.refreshed").value(false))
        .andExpect(jsonPath("$.reason").value(containsString("Only SUBMITTED")));

    assertThat(eventTypes(paymentId)).hasSize(4);
  }

  @Test
  void refreshLeavesAnApprovedPaymentAlone() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    paypal.failNextSubmission(
        new PayPalPayoutException("PayPal is unavailable", 503, "debug-refresh"));
    UUID paymentId = createPayment(agent, 1_200, ALICE, "Transient invoice");
    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post(refreshPath(paymentId))), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPROVED"))
        .andExpect(jsonPath("$.refreshed").value(false))
        .andExpect(jsonPath("$.reason").value(containsString("Only SUBMITTED")));

    assertThat(paymentState(paymentId)).isEqualTo("APPROVED");
    assertThat(eventTypes(paymentId)).doesNotContain("RECONCILED");
  }

  @Test
  void refreshTranslatesAPayPalFailureAndLeavesThePaymentSubmitted() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    UUID paymentId = createPayment(agent, 1_200, ALICE, "Broken refresh");
    paypal.failNextLookup(new PayPalPayoutException("PayPal is unavailable", 503, "debug-poll"));
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post(refreshPath(paymentId))), csrf))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("PAYPAL_UNAVAILABLE"));

    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(eventTypes(paymentId))
        .containsExactly("PAYMENT_REQUESTED", "DECISION_MADE", "PAYPAL_SUBMITTED");
  }

  @Test
  void unknownPaymentsReturn404ForEventsAndRefresh() throws Exception {
    UUID missing = UUID.randomUUID();
    String csrf = csrfToken();

    mockMvc
        .perform(mockAdmin(get("/api/v1/admin/payments/" + missing + "/events")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

    mockMvc
        .perform(withCsrf(mockAdmin(post(refreshPath(missing))), csrf))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
  }

  @Test
  void anAgentKeyCannotReachTheAdminPaymentRoutes() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    UUID paymentId = createPayment(agent, 1_200, ALICE, "Isolation invoice");
    String csrf = csrfToken();

    List<MockHttpServletRequestBuilder> requests =
        List.of(
            get("/api/v1/admin/payments"),
            get("/api/v1/admin/payments/" + paymentId + "/events"),
            post(refreshPath(paymentId)));

    for (MockHttpServletRequestBuilder request : requests) {
      mockMvc
          .perform(
              withCsrf(request, csrf).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
  }

  @Test
  void refreshWithoutCsrfIsRejected() throws Exception {
    CreatedAgent agent = createAgent("Alpha Agent");
    UUID paymentId = createPayment(agent, 1_200, ALICE, "CSRF invoice");

    mockMvc
        .perform(mockAdmin(post(refreshPath(paymentId))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));

    assertThat(paymentState(paymentId)).isEqualTo("SUBMITTED");
    assertThat(eventTypes(paymentId)).hasSize(3);
  }

  private void assertQueryCount(String path, int expected) throws Exception {
    countingJdbcTemplate.reset();
    mockMvc.perform(mockAdmin(get(path))).andExpect(status().isOk());
    assertThat(countingJdbcTemplate.executions())
        .as("SQL statements for %s", path)
        .isEqualTo(expected);
  }

  private CreatedAgent createAgent(String name) {
    return agentService.createAgent(name, 50_000L, 10_000L, List.of(ALICE));
  }

  private UUID createPayment(CreatedAgent agent, long amountMinor, String payeeEmail, String memo)
      throws Exception {
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
                                "payeeEmail", payeeEmail,
                                "amountMinor", amountMinor,
                                "currency", "USD",
                                "memo", memo))))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(readJson(result).get("paymentId").asText());
  }

  private String csrfToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/payments")))
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

  private static String refreshPath(UUID paymentId) {
    return "/api/v1/admin/payments/" + paymentId + "/refresh";
  }

  private String paypalBatchId(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT paypal_batch_id FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private String paymentState(UUID paymentId) {
    return jdbcTemplate.queryForObject(
        "SELECT state FROM payment_requests WHERE id = ?", String.class, paymentId);
  }

  private List<String> eventTypes(UUID paymentId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM ledger_events WHERE payment_id = ? ORDER BY id",
        String.class,
        paymentId);
  }

  private JsonNode readJson(MvcResult result) {
    try {
      return objectMapper.readTree(result.getResponse().getContentAsString());
    } catch (Exception exception) {
      throw new IllegalStateException("Response is not valid JSON", exception);
    }
  }

  /** Counts every prepared statement executed through the shared template. */
  static final class CountingJdbcTemplate extends JdbcTemplate {

    private final AtomicInteger executions = new AtomicInteger();

    CountingJdbcTemplate(DataSource dataSource) {
      super(dataSource);
    }

    @Override
    public <T> T query(
        PreparedStatementCreator psc, PreparedStatementSetter pss, ResultSetExtractor<T> rse) {
      executions.incrementAndGet();
      return super.query(psc, pss, rse);
    }

    @Override
    public <T> T execute(String sql, PreparedStatementCallback<T> action) {
      executions.incrementAndGet();
      return super.execute(sql, action);
    }

    int executions() {
      return executions.get();
    }

    void reset() {
      executions.set(0);
    }
  }
}
