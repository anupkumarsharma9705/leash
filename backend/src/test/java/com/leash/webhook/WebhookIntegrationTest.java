package com.leash.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leash.TestcontainersConfiguration;
import com.leash.webhook.domain.WebhookEvent;
import com.leash.webhook.infra.JdbcWebhookEventStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WebhookIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

  @Autowired private JdbcWebhookEventStore store;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void clearWebhookEvents() {
    jdbcTemplate.update("DELETE FROM webhook_events");
  }

  @Test
  void deduplicatesByPayPalEventId() {
    WebhookEvent event = event("WH-DB-1");

    assertThat(store.insertIfAbsent(event)).isTrue();
    assertThat(store.insertIfAbsent(event)).isFalse();
    assertThat(store.isProcessed("WH-DB-1")).isFalse();

    store.markProcessed("WH-DB-1", NOW.plusSeconds(1));

    assertThat(store.isProcessed("WH-DB-1")).isTrue();
    assertThat(store.insertIfAbsent(event)).isFalse();
  }

  @Test
  void persistsRawBodyAsJsonb() {
    store.insertIfAbsent(event("WH-DB-2"));

    String senderItemId =
        jdbcTemplate.queryForObject(
            "SELECT raw_body -> 'resource' ->> 'sender_item_id' FROM webhook_events WHERE event_id = ?",
            String.class,
            "WH-DB-2");
    assertThat(senderItemId).isEqualTo("pay_123");
  }

  @Test
  void webhookEndpointIsPublicAndCsrfExempt() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(new byte[0]))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_EVENT"));
  }

  private static WebhookEvent event(String eventId) {
    return new WebhookEvent(
        eventId,
        "PAYMENT.PAYOUTS-ITEM.SUCCEEDED",
        "{\"id\":\""
            + eventId
            + "\",\"event_type\":\"PAYMENT.PAYOUTS-ITEM.SUCCEEDED\","
            + "\"resource\":{\"sender_item_id\":\"pay_123\"}}",
        NOW);
  }
}
