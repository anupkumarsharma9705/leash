package com.leash.ledger.infra;

import com.leash.ledger.app.LedgerStore;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL-backed append-only ledger. Only INSERT and SELECT statements exist here. */
@Repository
public class JdbcLedgerStore implements LedgerStore {

  private static final String INSERT =
      """
      INSERT INTO ledger_events (agent_id, payment_id, event_type, actor, payload, created_at)
      VALUES (?, ?, ?, ?, ?::jsonb, ?)
      RETURNING id
      """;

  private static final String FIND_BY_PAYMENT =
      """
      SELECT id, agent_id, payment_id, event_type, actor, payload::text AS payload_json, created_at
      FROM ledger_events
      WHERE payment_id = ?
      ORDER BY id
      """;

  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcLedgerStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public LedgerEvent insert(LedgerEvent event) {
    Long id =
        jdbcTemplate.queryForObject(
            INSERT,
            Long.class,
            event.agentId(),
            event.paymentId(),
            event.eventType().name(),
            event.actor(),
            objectMapper.writeValueAsString(event.payload()),
            OffsetDateTime.ofInstant(event.createdAt(), java.time.ZoneOffset.UTC));

    return new LedgerEvent(
        id,
        event.agentId(),
        event.paymentId(),
        event.eventType(),
        event.actor(),
        event.payload(),
        event.createdAt());
  }

  @Override
  public List<LedgerEvent> findByPaymentId(UUID paymentId) {
    return jdbcTemplate.query(
        FIND_BY_PAYMENT, (resultSet, rowNumber) -> mapEvent(resultSet), paymentId);
  }

  private LedgerEvent mapEvent(ResultSet resultSet) throws SQLException {
    return new LedgerEvent(
        resultSet.getLong("id"),
        resultSet.getObject("agent_id", UUID.class),
        resultSet.getObject("payment_id", UUID.class),
        LedgerEventType.valueOf(resultSet.getString("event_type")),
        resultSet.getString("actor"),
        objectMapper.readValue(resultSet.getString("payload_json"), PAYLOAD_TYPE),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
