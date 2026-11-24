package com.leash.agent.infra;

import com.leash.agent.app.AgentStore;
import com.leash.agent.app.ApiKeyCandidate;
import com.leash.agent.app.ApiKeyRecord;
import com.leash.agent.domain.Agent;
import com.leash.policy.AgentPolicy;
import com.leash.policy.AgentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed agent, policy, and API-key store. */
@Repository
public class JdbcAgentStore implements AgentStore {

  private static final String INSERT_AGENT =
      "INSERT INTO agents (id, name, status, created_at) VALUES (?, ?, ?, ?)";
  private static final String INSERT_POLICY =
      """
      INSERT INTO policies
          (agent_id, weekly_limit_minor, auto_approve_threshold_minor, currency, updated_at)
      VALUES (?, ?, ?, 'USD', ?)
      """;
  private static final String INSERT_PAYEE =
      "INSERT INTO policy_payees (agent_id, email_normalized) VALUES (?, ?)";
  private static final String INSERT_API_KEY =
      """
      INSERT INTO api_keys (id, agent_id, key_hash, prefix, created_at, revoked_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """;
  private static final String FIND_CANDIDATES =
      """
      SELECT k.id AS api_key_id,
             k.agent_id,
             a.name AS agent_name,
             a.status AS agent_status,
             k.key_hash,
             k.revoked_at
      FROM api_keys k
      JOIN agents a ON a.id = k.agent_id
      WHERE k.prefix = ?
      ORDER BY k.created_at
      """;
  private static final String MARK_USED = "UPDATE api_keys SET last_used_at = ? WHERE id = ?";
  private static final String LOCK_AGENT =
      "SELECT id, name, status, created_at FROM agents WHERE id = ? FOR UPDATE";
  private static final String FIND_AGENT =
      "SELECT id, name, status, created_at FROM agents WHERE id = ?";
  private static final String FIND_POLICY =
      """
      SELECT weekly_limit_minor, auto_approve_threshold_minor
      FROM policies WHERE agent_id = ?
      """;
  private static final String FIND_PAYEES =
      "SELECT email_normalized FROM policy_payees WHERE agent_id = ? ORDER BY email_normalized";
  private static final String LIST_AGENTS =
      """
      SELECT a.id, a.name, a.status, a.created_at,
             p.weekly_limit_minor, p.auto_approve_threshold_minor
      FROM agents a
      JOIN policies p ON p.agent_id = a.id
      ORDER BY a.created_at, a.id
      """;
  private static final String LIST_ALL_PAYEES =
      """
      SELECT agent_id, email_normalized
      FROM policy_payees
      ORDER BY agent_id, email_normalized
      """;
  private static final String UPDATE_POLICY =
      """
      UPDATE policies
      SET weekly_limit_minor = ?, auto_approve_threshold_minor = ?, updated_at = ?
      WHERE agent_id = ?
      """;
  private static final String DELETE_PAYEES = "DELETE FROM policy_payees WHERE agent_id = ?";
  private static final String UPDATE_STATUS = "UPDATE agents SET status = ? WHERE id = ?";
  private static final String REVOKE_ACTIVE_KEYS =
      "UPDATE api_keys SET revoked_at = ? WHERE agent_id = ? AND revoked_at IS NULL";

  private final JdbcTemplate jdbcTemplate;

  public JdbcAgentStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void insertAgent(Agent agent) {
    jdbcTemplate.update(
        INSERT_AGENT,
        agent.id(),
        agent.name(),
        agent.status().name(),
        Timestamp.from(agent.createdAt()));
    jdbcTemplate.update(
        INSERT_POLICY,
        agent.id(),
        agent.policy().weeklyLimitMinor(),
        agent.policy().autoApproveThresholdMinor(),
        Timestamp.from(agent.createdAt()));

    List<Object[]> payees =
        agent.policy().allowedPayees().stream()
            .<Object[]>map(email -> new Object[] {agent.id(), email})
            .toList();
    if (!payees.isEmpty()) {
      jdbcTemplate.batchUpdate(INSERT_PAYEE, payees);
    }
  }

  @Override
  public void insertApiKey(ApiKeyRecord apiKey) {
    jdbcTemplate.update(
        INSERT_API_KEY,
        apiKey.id(),
        apiKey.agentId(),
        apiKey.keyHash(),
        apiKey.prefix(),
        Timestamp.from(apiKey.createdAt()),
        apiKey.revokedAt() == null ? null : Timestamp.from(apiKey.revokedAt()));
  }

  @Override
  public List<ApiKeyCandidate> findApiKeyCandidates(String prefix) {
    return jdbcTemplate.query(
        FIND_CANDIDATES, (resultSet, rowNumber) -> mapCandidate(resultSet), prefix);
  }

  @Override
  public void markApiKeyUsed(UUID apiKeyId, Instant usedAt) {
    jdbcTemplate.update(MARK_USED, Timestamp.from(usedAt), apiKeyId);
  }

  @Override
  public java.util.Optional<Agent> findAgent(UUID agentId) {
    return loadAgent(FIND_AGENT, agentId);
  }

  @Override
  public java.util.Optional<Agent> lockAgent(UUID agentId) {
    return loadAgent(LOCK_AGENT, agentId);
  }

  @Override
  public List<Agent> listAgents() {
    Map<UUID, List<String>> payeesByAgent = new HashMap<>();
    List<PayeeRow> payeeRows =
        jdbcTemplate.query(
            LIST_ALL_PAYEES,
            (resultSet, rowNumber) ->
                new PayeeRow(
                    resultSet.getObject("agent_id", UUID.class),
                    resultSet.getString("email_normalized")));
    for (PayeeRow row : payeeRows) {
      payeesByAgent.computeIfAbsent(row.agentId(), key -> new ArrayList<>()).add(row.email());
    }

    return jdbcTemplate.query(
        LIST_AGENTS,
        (resultSet, rowNumber) -> {
          UUID agentId = resultSet.getObject("id", UUID.class);
          return new Agent(
              agentId,
              resultSet.getString("name"),
              AgentStatus.valueOf(resultSet.getString("status")),
              new AgentPolicy(
                  resultSet.getLong("weekly_limit_minor"),
                  resultSet.getLong("auto_approve_threshold_minor"),
                  Set.copyOf(payeesByAgent.getOrDefault(agentId, List.of()))),
              resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant());
        });
  }

  @Override
  public void updatePolicy(UUID agentId, AgentPolicy policy, Instant updatedAt) {
    int updated =
        jdbcTemplate.update(
            UPDATE_POLICY,
            policy.weeklyLimitMinor(),
            policy.autoApproveThresholdMinor(),
            Timestamp.from(updatedAt),
            agentId);
    if (updated != 1) {
      throw new IllegalStateException("Agent has no policy: " + agentId);
    }

    jdbcTemplate.update(DELETE_PAYEES, agentId);
    List<Object[]> payees =
        policy.allowedPayees().stream()
            .sorted()
            .<Object[]>map(email -> new Object[] {agentId, email})
            .toList();
    if (!payees.isEmpty()) {
      jdbcTemplate.batchUpdate(INSERT_PAYEE, payees);
    }
  }

  @Override
  public void updateStatus(UUID agentId, AgentStatus status) {
    jdbcTemplate.update(UPDATE_STATUS, status.name(), agentId);
  }

  @Override
  public int revokeActiveApiKeys(UUID agentId, Instant revokedAt) {
    return jdbcTemplate.update(REVOKE_ACTIVE_KEYS, Timestamp.from(revokedAt), agentId);
  }

  private java.util.Optional<Agent> loadAgent(String sql, UUID agentId) {
    List<AgentRow> rows =
        jdbcTemplate.query(
            sql,
            (resultSet, rowNumber) ->
                new AgentRow(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("name"),
                    AgentStatus.valueOf(resultSet.getString("status")),
                    resultSet.getObject("created_at", java.time.OffsetDateTime.class).toInstant()),
            agentId);
    if (rows.isEmpty()) {
      return java.util.Optional.empty();
    }

    AgentRow row = rows.getFirst();
    List<PolicyRow> policies =
        jdbcTemplate.query(
            FIND_POLICY,
            (resultSet, rowNumber) ->
                new PolicyRow(
                    resultSet.getLong("weekly_limit_minor"),
                    resultSet.getLong("auto_approve_threshold_minor")),
            agentId);
    if (policies.isEmpty()) {
      throw new IllegalStateException("Agent has no policy: " + agentId);
    }
    PolicyRow policy = policies.getFirst();
    List<String> payees = jdbcTemplate.queryForList(FIND_PAYEES, String.class, agentId);

    return java.util.Optional.of(
        new Agent(
            row.id(),
            row.name(),
            row.status(),
            new com.leash.policy.AgentPolicy(
                policy.weeklyLimitMinor(),
                policy.autoApproveThresholdMinor(),
                java.util.Set.copyOf(payees)),
            row.createdAt()));
  }

  private static ApiKeyCandidate mapCandidate(ResultSet resultSet) throws SQLException {
    return new ApiKeyCandidate(
        resultSet.getObject("api_key_id", UUID.class),
        resultSet.getObject("agent_id", UUID.class),
        resultSet.getString("agent_name"),
        AgentStatus.valueOf(resultSet.getString("agent_status")),
        resultSet.getString("key_hash"),
        instantOrNull(resultSet.getTimestamp("revoked_at")));
  }

  private static Instant instantOrNull(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record AgentRow(UUID id, String name, AgentStatus status, Instant createdAt) {}

  private record PayeeRow(UUID agentId, String email) {}

  private record PolicyRow(long weeklyLimitMinor, long autoApproveThresholdMinor) {}
}
