package com.leash.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.CreatedAgent;
import com.leash.policy.BudgetPeriod;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the Slice 3.1 administrative contract.
 *
 * <p>Each test exercises the real filter chain: HTTP Basic with the in-memory BCrypt credential,
 * the cookie-backed CSRF token from {@code csrf.spa()}, and the admin agent endpoints. The agent
 * API key is deliberately exercised against every admin route to prove the two chains stay
 * separate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAgentApiIntegrationTest {

  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "admin-test-password";
  private static final String CSRF_COOKIE = "XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  private static final String CREATE_BODY =
      """
      {
        "name": "Research Agent",
        "weeklyLimitMinor": 50000,
        "autoApproveThresholdMinor": 10000,
        "allowedPayees": ["Alice@Example.com", "bob@example.com"]
      }
      """;

  @Autowired private AgentService agentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void cleanDatabase() {
    jdbcTemplate.update("DELETE FROM approvals");
    jdbcTemplate.update("DELETE FROM payment_requests");
    jdbcTemplate.update("DELETE FROM api_keys");
    jdbcTemplate.update("DELETE FROM policy_payees");
    jdbcTemplate.update("DELETE FROM policies");
    jdbcTemplate.update("DELETE FROM agents");
  }

  @Test
  void createReturnsThePlaintextKeyOnceAndTheListNeverExposesIt() throws Exception {
    String csrf = csrfToken();

    MvcResult created =
        mockMvc
            .perform(
                withCsrf(
                    mockAdmin(post("/api/v1/admin/agents"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY),
                    csrf))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.agentId").isString())
            .andExpect(jsonPath("$.name").value("Research Agent"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.policy.weeklyLimitMinor").value(50_000))
            .andExpect(jsonPath("$.policy.autoApproveThresholdMinor").value(10_000))
            .andExpect(jsonPath("$.policy.allowedPayees.length()").value(2))
            .andExpect(jsonPath("$.policy.allowedPayees[0]").value("alice@example.com"))
            .andExpect(jsonPath("$.policy.allowedPayees[1]").value("bob@example.com"))
            .andExpect(jsonPath("$.budget.limitMinor").value(50_000))
            .andExpect(jsonPath("$.budget.spentMinor").value(0))
            .andExpect(jsonPath("$.budget.reservedMinor").value(0))
            .andExpect(jsonPath("$.budget.remainingMinor").value(50_000))
            .andExpect(jsonPath("$.apiKey").isString())
            .andReturn();

    String apiKey = readJson(created).get("apiKey").asText();
    assertThat(apiKey).startsWith("lsh_").hasSize(47);

    mockMvc
        .perform(get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(50_000));

    String list =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/agents")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(list).doesNotContain(apiKey).doesNotContain("apiKey");
  }

  @Test
  void anAgentKeyCannotCallAnyAdminEndpoint() throws Exception {
    CreatedAgent agent = createAgent();
    UUID agentId = agent.agent().id();
    String csrf = csrfToken();

    List<MockHttpServletRequestBuilder> requests =
        List.of(
            post("/api/v1/admin/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY),
            get("/api/v1/admin/agents"),
            put("/api/v1/admin/agents/" + agentId + "/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody(20_000, 5_000, "alice@example.com")),
            post("/api/v1/admin/agents/" + agentId + "/suspend"),
            post("/api/v1/admin/agents/" + agentId + "/resume"),
            post("/api/v1/admin/agents/" + agentId + "/rotate-key"));

    for (MockHttpServletRequestBuilder request : requests) {
      mockMvc
          .perform(
              withCsrf(request, csrf).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
          .andExpect(jsonPath("$.status").value(401));
    }
  }

  @Test
  void missingAdminCredentialsReturnAProblemDetail() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/agents"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            header()
                .string(
                    HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"leash-admin\", charset=\"UTF-8\""))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.instance").value("/api/v1/admin/agents"));

    mockMvc
        .perform(get("/api/v1/admin/agents").with(httpBasic(ADMIN_USERNAME, "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void adminWriteWithoutACsrfTokenIsRejected() throws Exception {
    CreatedAgent agent = createAgent();

    mockMvc
        .perform(
            mockAdmin(put("/api/v1/admin/agents/" + agent.agent().id() + "/policy"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody(20_000, 5_000, "alice@example.com")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"))
        .andExpect(jsonPath("$.status").value(403));

    mockMvc
        .perform(mockAdmin(post("/api/v1/admin/agents/" + agent.agent().id() + "/suspend")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
  }

  @Test
  void policyEditWritesALedgerFactWithPreviousAndNewValues() throws Exception {
    CreatedAgent agent = createAgent();
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(put("/api/v1/admin/agents/" + agent.agent().id() + "/policy"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(policyBody(80_000, 20_000, "carol@example.com")),
                csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentId").value(agent.agent().id().toString()))
        .andExpect(jsonPath("$.policy.weeklyLimitMinor").value(80_000))
        .andExpect(jsonPath("$.policy.autoApproveThresholdMinor").value(20_000))
        .andExpect(jsonPath("$.policy.allowedPayees[0]").value("carol@example.com"));

    Map<String, Object> event = latestLedgerEvent(agent.agent().id(), "POLICY_CHANGED");
    assertThat(event.get("actor")).isEqualTo(ADMIN_USERNAME);

    JsonNode payload = objectMapper.readTree((String) event.get("payload"));
    assertThat(payload.get("previousWeeklyLimitMinor").asLong()).isEqualTo(50_000L);
    assertThat(payload.get("weeklyLimitMinor").asLong()).isEqualTo(80_000L);
    assertThat(payload.get("previousAutoApproveThresholdMinor").asLong()).isEqualTo(10_000L);
    assertThat(payload.get("autoApproveThresholdMinor").asLong()).isEqualTo(20_000L);
    assertThat(payload.get("previousAllowedPayees").size()).isEqualTo(2);
    assertThat(payload.get("previousAllowedPayees").get(0).asText()).isEqualTo("alice@example.com");
    assertThat(payload.get("allowedPayees").size()).isEqualTo(1);
    assertThat(payload.get("allowedPayees").get(0).asText()).isEqualTo("carol@example.com");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT weekly_limit_minor FROM policies WHERE agent_id = ?",
                Long.class,
                agent.agent().id()))
        .isEqualTo(80_000L);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT email_normalized FROM policy_payees WHERE agent_id = ?",
                String.class,
                agent.agent().id()))
        .containsExactly("carol@example.com");
  }

  @Test
  void suspendAndResumeAreIdempotentAndLedgered() throws Exception {
    CreatedAgent agent = createAgent();
    UUID agentId = agent.agent().id();
    String csrf = csrfToken();

    mockMvc
        .perform(withCsrf(mockAdmin(post("/api/v1/admin/agents/" + agentId + "/suspend")), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AGENT_SUSPENDED"));

    // A second suspend is a no-op and must not append another ledger fact.
    mockMvc
        .perform(withCsrf(mockAdmin(post("/api/v1/admin/agents/" + agentId + "/suspend")), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));

    assertThat(ledgerCount(agentId, "AGENT_STATUS_CHANGED")).isEqualTo(1);

    mockMvc
        .perform(withCsrf(mockAdmin(post("/api/v1/admin/agents/" + agentId + "/resume")), csrf))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isOk());

    assertThat(ledgerCount(agentId, "AGENT_STATUS_CHANGED")).isEqualTo(2);

    List<String> payloads =
        jdbcTemplate.queryForList(
            "SELECT payload::text FROM ledger_events WHERE agent_id = ?"
                + " AND event_type = 'AGENT_STATUS_CHANGED' ORDER BY id",
            String.class,
            agentId);
    JsonNode suspended = objectMapper.readTree(payloads.get(0));
    assertThat(suspended.get("previousStatus").asText()).isEqualTo("ACTIVE");
    assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
    JsonNode resumed = objectMapper.readTree(payloads.get(1));
    assertThat(resumed.get("previousStatus").asText()).isEqualTo("SUSPENDED");
    assertThat(resumed.get("status").asText()).isEqualTo("ACTIVE");
  }

  @Test
  void rotateKeyInvalidatesTheOldKeyAndTheNewKeyWorks() throws Exception {
    CreatedAgent agent = createAgent();
    UUID agentId = agent.agent().id();
    String csrf = csrfToken();

    MvcResult rotated =
        mockMvc
            .perform(
                withCsrf(mockAdmin(post("/api/v1/admin/agents/" + agentId + "/rotate-key")), csrf))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentId").value(agentId.toString()))
            .andExpect(jsonPath("$.apiKey").isString())
            .andReturn();

    String newKey = readJson(rotated).get("apiKey").asText();
    assertThat(newKey).startsWith("lsh_").hasSize(47).isNotEqualTo(agent.apiKey());

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.apiKey()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));

    mockMvc
        .perform(get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + newKey))
        .andExpect(status().isOk());

    Map<String, Object> event = latestLedgerEvent(agentId, "API_KEY_ROTATED");
    String payloadText = (String) event.get("payload");
    assertThat(event.get("actor")).isEqualTo(ADMIN_USERNAME);
    assertThat(payloadText).doesNotContain(newKey).contains(newKey.substring(0, 8));
    JsonNode payload = objectMapper.readTree(payloadText);
    assertThat(payload.get("revokedKeyCount").asInt()).isEqualTo(1);
    assertThat(payload.get("prefix").asText()).isEqualTo(newKey.substring(0, 8));
  }

  @Test
  void listReportsThisWeekSpentAndReservedAgainstTheLimit() throws Exception {
    CreatedAgent agent = createAgent();
    UUID agentId = agent.agent().id();
    Instant now = Instant.now();
    Instant lastWeek = BudgetPeriod.weekContaining(now).startInclusive().minusSeconds(1);

    insertPayment(agentId, "SUBMITTED", "APPROVE", 1_200, now);
    insertPayment(agentId, "APPROVED", "APPROVE", 800, now);
    insertPayment(agentId, "PENDING_APPROVAL", "ESCALATE", 5_000, now);
    insertPayment(agentId, "FAILED", "APPROVE", 3_000, now);
    insertPayment(agentId, "DENIED", "DENY", 4_000, now);
    insertPayment(agentId, "SUBMITTED", "APPROVE", 9_999, lastWeek);

    JsonNode agentNode =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/agents")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].budget.limitMinor").value(50_000))
            .andExpect(jsonPath("$[0].budget.spentMinor").value(2_000))
            .andExpect(jsonPath("$[0].budget.reservedMinor").value(5_000))
            .andExpect(jsonPath("$[0].budget.remainingMinor").value(43_000))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .transform(this::readJson);

    assertThat(agentNode.get(0).get("agentId").asText()).isEqualTo(agentId.toString());
  }

  @Test
  void invalidCreateBodiesAreRejectedWithValidationDetails() throws Exception {
    String csrf = csrfToken();

    mockMvc
        .perform(
            withCsrf(
                mockAdmin(post("/api/v1/admin/agents"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": " ",
                          "weeklyLimitMinor": -1,
                          "autoApproveThresholdMinor": -5,
                          "allowedPayees": ["not-an-email"]
                        }
                        """),
                csrf))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.details").isArray());
  }

  @Test
  void unknownAgentIdsReturnAgentNotFound() throws Exception {
    String csrf = csrfToken();
    UUID missing = UUID.randomUUID();

    List<MockHttpServletRequestBuilder> requests =
        List.of(
            put("/api/v1/admin/agents/" + missing + "/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyBody(10_000, 1_000, "alice@example.com")),
            post("/api/v1/admin/agents/" + missing + "/suspend"),
            post("/api/v1/admin/agents/" + missing + "/resume"),
            post("/api/v1/admin/agents/" + missing + "/rotate-key"));

    for (MockHttpServletRequestBuilder request : requests) {
      mockMvc
          .perform(withCsrf(mockAdmin(request), csrf))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }
  }

  private CreatedAgent createAgent() {
    return agentService.createAgent(
        "Demo Agent", 50_000L, 10_000L, List.of("alice@example.com", "bob@example.com"));
  }

  private String csrfToken() throws Exception {
    MvcResult result =
        mockMvc
            .perform(mockAdmin(get("/api/v1/admin/agents")))
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

  private Map<String, Object> latestLedgerEvent(UUID agentId, String eventType) {
    return jdbcTemplate.queryForMap(
        "SELECT actor, payload::text AS payload FROM ledger_events"
            + " WHERE agent_id = ? AND event_type = ? ORDER BY id DESC LIMIT 1",
        agentId,
        eventType);
  }

  private long ledgerCount(UUID agentId, String eventType) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM ledger_events WHERE agent_id = ? AND event_type = ?",
        Long.class,
        agentId,
        eventType);
  }

  private void insertPayment(
      UUID agentId, String state, String outcome, long amountMinor, Instant createdAt) {
    String reasons =
        "DENY".equals(outcome)
            ? "[{\"code\":\"PAYEE_NOT_ALLOWED\",\"message\":\"not allowed\"}]"
            : "[]";
    jdbcTemplate.update(
        """
        INSERT INTO payment_requests (
            id, agent_id, idempotency_key, request_fingerprint, payee_email, amount_minor,
            currency, memo, state, decision_outcome, decision_reasons, budget_limit_minor,
            budget_spent_minor, budget_reserved_minor, version, created_at, updated_at)
        VALUES (?, ?, ?, ?, 'alice@example.com', ?, 'USD', 'invoice', ?, ?, ?::jsonb,
                50000, 0, 0, 0, ?, ?)
        """,
        UUID.randomUUID(),
        agentId,
        UUID.randomUUID().toString(),
        "a".repeat(64),
        amountMinor,
        state,
        outcome,
        reasons,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
  }

  private static String policyBody(long weeklyLimit, long threshold, String... payees) {
    String allowed =
        Arrays.stream(payees).map(payee -> "\"" + payee + "\"").collect(Collectors.joining(","));
    return "{\"weeklyLimitMinor\":"
        + weeklyLimit
        + ",\"autoApproveThresholdMinor\":"
        + threshold
        + ",\"allowedPayees\":["
        + allowed
        + "]}";
  }

  private JsonNode readJson(MvcResult result) {
    try {
      return objectMapper.readTree(result.getResponse().getContentAsString());
    } catch (Exception exception) {
      throw new IllegalStateException("Response is not valid JSON", exception);
    }
  }

  private JsonNode readJson(String json) {
    return objectMapper.readTree(json);
  }
}
