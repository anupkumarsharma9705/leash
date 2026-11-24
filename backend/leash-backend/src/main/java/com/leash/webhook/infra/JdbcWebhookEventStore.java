package com.leash.webhook.infra;

import com.leash.webhook.app.WebhookEventStore;
import com.leash.webhook.domain.WebhookEvent;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed webhook event store; the primary key is the dedupe boundary. */
@Repository
public class JdbcWebhookEventStore implements WebhookEventStore {

  private static final String INSERT =
      """
      INSERT INTO webhook_events (event_id, event_type, raw_body, received_at)
      VALUES (?, ?, CAST(? AS JSONB), ?)
      ON CONFLICT (event_id) DO NOTHING
      """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcWebhookEventStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public boolean insertIfAbsent(WebhookEvent event) {
    int updated =
        jdbcTemplate.update(
            INSERT,
            event.eventId(),
            event.eventType(),
            event.rawBody(),
            Timestamp.from(event.receivedAt()));
    return updated == 1;
  }

  @Override
  public boolean isProcessed(String eventId) {
    Boolean processed =
        jdbcTemplate.queryForObject(
            "SELECT processed_at IS NOT NULL FROM webhook_events WHERE event_id = ?",
            Boolean.class,
            eventId);
    return Boolean.TRUE.equals(processed);
  }

  @Override
  public void markProcessed(String eventId, Instant processedAt) {
    jdbcTemplate.update(
        "UPDATE webhook_events SET processed_at = ? WHERE event_id = ? AND processed_at IS NULL",
        Timestamp.from(processedAt),
        eventId);
  }
}
