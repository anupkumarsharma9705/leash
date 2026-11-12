package com.leash.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.leash.TestcontainersConfiguration;
import com.leash.agent.app.AgentService;
import com.leash.agent.domain.AgentSuspendedException;
import com.leash.agent.domain.ApiKeyHasher;
import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.agent.domain.CreatedAgent;
import com.leash.agent.domain.InvalidApiKeyException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AgentApiKeyIntegrationTest {

  @Autowired private AgentService agentService;
  @Autowired private ApiKeyHasher hasher;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;

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
  void createsAgentAndStoresOnlyTheHashAndPrefix() {
    CreatedAgent created = createAgent();
    String plaintext = created.apiKey();

    Map<String, Object> keyRow =
        jdbcTemplate.queryForMap(
            "SELECT key_hash, prefix, last_used_at FROM api_keys WHERE agent_id = ?",
            created.agent().id());

    assertThat(keyRow.get("key_hash")).isEqualTo(hasher.hash(plaintext));
    assertThat(keyRow.get("prefix")).isEqualTo(plaintext.substring(0, 8));
    assertThat(keyRow.get("key_hash")).isNotEqualTo(plaintext);
    assertThat(keyRow.get("prefix")).isNotEqualTo(plaintext);
    assertThat(keyRow.get("last_used_at")).isNull();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT name FROM agents WHERE id = ?", String.class, created.agent().id()))
        .isEqualTo("Demo Agent");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM agents WHERE id = ?", String.class, created.agent().id()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT weekly_limit_minor FROM policies WHERE agent_id = ?",
                Long.class,
                created.agent().id()))
        .isEqualTo(50_000L);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT email_normalized FROM policy_payees WHERE agent_id = ?"
                    + " ORDER BY email_normalized",
                String.class,
                created.agent().id()))
        .containsExactly("alice@example.com", "bob@example.com");
  }

  @Test
  void authenticatesAValidKeyAndMarksItUsed() {
    CreatedAgent created = createAgent();

    AuthenticatedAgent authenticated = agentService.authenticate(created.apiKey());

    assertThat(authenticated.agentId()).isEqualTo(created.agent().id());
    assertThat(authenticated.name()).isEqualTo("Demo Agent");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_used_at FROM api_keys WHERE agent_id = ?",
                Timestamp.class,
                created.agent().id()))
        .isNotNull();
  }

  @Test
  void rejectsAKeyThatOnlySharesThePrefix() {
    CreatedAgent created = createAgent();
    String plaintext = created.apiKey();
    char replacement = plaintext.endsWith("a") ? 'b' : 'a';
    String wrongKey = plaintext.substring(0, plaintext.length() - 1) + replacement;

    assertThat(wrongKey.substring(0, 8)).isEqualTo(plaintext.substring(0, 8));
    assertThatThrownBy(() -> agentService.authenticate(wrongKey))
        .isInstanceOf(InvalidApiKeyException.class)
        .hasMessageNotContaining(wrongKey);
  }

  @Test
  void rejectsAMalformedKeyWithoutTouchingTheDatabase() {
    assertThatThrownBy(() -> agentService.authenticate("lsh_short"))
        .isInstanceOf(InvalidApiKeyException.class);
    assertThatThrownBy(() -> agentService.authenticate(null))
        .isInstanceOf(InvalidApiKeyException.class);
  }

  @Test
  void rejectsAValidKeyForASuspendedAgent() {
    CreatedAgent created = createAgent();
    jdbcTemplate.update(
        "UPDATE agents SET status = 'SUSPENDED' WHERE id = ?", created.agent().id());

    assertThatThrownBy(() -> agentService.authenticate(created.apiKey()))
        .isInstanceOfSatisfying(
            AgentSuspendedException.class,
            exception -> assertThat(exception.agentId()).isEqualTo(created.agent().id()));
  }

  @Test
  void rejectsARevokedKey() {
    CreatedAgent created = createAgent();
    jdbcTemplate.update(
        "UPDATE api_keys SET revoked_at = now() WHERE agent_id = ?", created.agent().id());

    assertThatThrownBy(() -> agentService.authenticate(created.apiKey()))
        .isInstanceOf(InvalidApiKeyException.class);
  }

  @Test
  void validAgentKeyPassesTheSecurityChain() throws Exception {
    CreatedAgent created = createAgent();

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + created.apiKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limitMinor").value(50_000))
        .andExpect(jsonPath("$.spentMinor").value(0))
        .andExpect(jsonPath("$.reservedMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(50_000));
  }

  @Test
  void missingApiKeyReturnsProblemDetail() throws Exception {
    mockMvc
        .perform(get("/api/v1/budget"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void unknownApiKeyReturnsProblemDetail() throws Exception {
    CreatedAgent created = createAgent();
    String plaintext = created.apiKey();
    String wrongKey = plaintext.substring(0, plaintext.length() - 1) + "z";

    mockMvc
        .perform(get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongKey))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
  }

  @Test
  void suspendedAgentReturnsForbidden() throws Exception {
    CreatedAgent created = createAgent();
    jdbcTemplate.update(
        "UPDATE agents SET status = 'SUSPENDED' WHERE id = ?", created.agent().id());

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Bearer " + created.apiKey()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AGENT_SUSPENDED"));
  }

  @Test
  void nonBearerSchemeIsRejected() throws Exception {
    CreatedAgent created = createAgent();

    mockMvc
        .perform(
            get("/api/v1/budget").header(HttpHeaders.AUTHORIZATION, "Basic " + created.apiKey()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
  }

  @Test
  void agentKeyCannotAuthenticateToAdminRoutes() throws Exception {
    CreatedAgent created = createAgent();

    mockMvc
        .perform(
            get("/api/v1/admin/agents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + created.apiKey()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void plaintextKeyIsNeverLogged() {
    Logger packageLogger = (Logger) LoggerFactory.getLogger("com.leash.agent");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    packageLogger.addAppender(appender);
    try {
      CreatedAgent created = createAgent();
      String plaintext = created.apiKey();
      String wrongKey = plaintext.substring(0, plaintext.length() - 1) + "z";

      assertThatThrownBy(() -> agentService.authenticate(wrongKey))
          .isInstanceOf(InvalidApiKeyException.class);

      assertThat(created.toString()).doesNotContain(plaintext).contains("***");
      assertThat(new InvalidApiKeyException().getMessage()).doesNotContain(plaintext);
      assertThat(appender.list)
          .isNotEmpty()
          .allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(plaintext, wrongKey);
            if (event.getThrowableProxy() != null) {
              assertThat(event.getThrowableProxy().getMessage())
                  .doesNotContain(plaintext, wrongKey);
            }
          });
    } finally {
      packageLogger.detachAppender(appender);
    }
  }

  private CreatedAgent createAgent() {
    return agentService.createAgent(
        "Demo Agent", 50_000L, 10_000L, List.of(" Alice@Example.com ", "bob@example.com"));
  }
}
