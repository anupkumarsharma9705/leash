package com.leash.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leash.TestcontainersConfiguration;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.ledger.domain.SecretInLedgerPayloadException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import({
  TestcontainersConfiguration.class,
  LedgerServiceIntegrationTest.FixedClockConfiguration.class
})
class LedgerServiceIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Autowired private LedgerService ledgerService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @TestConfiguration
  static class FixedClockConfiguration {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Test
  void appendsAndReadsBackAnEvent() {
    UUID agentId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();

    LedgerEvent appended =
        inTransaction(() ->
            ledgerService.append(
                agentId,
                paymentId,
                LedgerEventType.PAYMENT_REQUESTED,
                "agent",
                Map.of("amountMinor", 1200, "currency", "USD", "memo", "Invoice inv-1")));

    assertThat(appended.id()).isPositive();
    assertThat(appended.createdAt()).isEqualTo(NOW);
    assertThat(appended.agentId()).isEqualTo(agentId);
    assertThat(appended.paymentId()).isEqualTo(paymentId);

    assertThat(ledgerService.eventsForPayment(paymentId)).containsExactly(appended);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT event_type, actor, payload ->> 'amountMinor' AS amount_minor,"
                + " payload ->> 'currency' AS currency"
                + " FROM ledger_events WHERE id = ?",
            appended.id());
    assertThat(row.get("event_type")).isEqualTo("PAYMENT_REQUESTED");
    assertThat(row.get("actor")).isEqualTo("agent");
    assertThat(row.get("amount_minor")).isEqualTo("1200");
    assertThat(row.get("currency")).isEqualTo("USD");
  }

  @Test
  void returnsNoEventsForAnUnknownPayment() {
    assertThat(ledgerService.eventsForPayment(UUID.randomUUID())).isEmpty();
  }

  @Test
  void rejectsUpdateAtTheDatabase() {
    LedgerEvent event = appendSimpleEvent();

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "UPDATE ledger_events SET actor = 'tampered' WHERE id = ?", event.id()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void rejectsDeleteAtTheDatabase() {
    LedgerEvent event = appendSimpleEvent();

    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM ledger_events WHERE id = ?", event.id()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");

    assertThat(ledgerService.eventsForPayment(event.paymentId())).hasSize(1);
  }

  @Test
  void rejectsTruncateAtTheDatabase() {
    appendSimpleEvent();

    assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE ledger_events"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void rejectsAForbiddenFieldName() {
    UUID paymentId = UUID.randomUUID();

    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    paymentId,
                    LedgerEventType.DECISION_MADE,
                    "policy-engine",
                    Map.of("apiKey", "not-a-real-key"))))
        .isInstanceOf(SecretInLedgerPayloadException.class)
        .hasMessageContaining("payload.apiKey")
        .hasMessageNotContaining("not-a-real-key");

    assertThat(rowCountForPayment(paymentId)).isZero();
  }

  @Test
  void rejectsNestedSecretFields() {
    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.DECISION_MADE,
                    "policy-engine",
                    Map.of("detail", Map.of("clientSecret", "shh")))))
        .isInstanceOf(SecretInLedgerPayloadException.class)
        .hasMessageContaining("payload.detail.clientSecret")
        .hasMessageNotContaining("shh");
  }

  @Test
  void rejectsValuesThatLookLikeApiKeysEvenUnderInnocentFieldNames() {
    String key = "lsh_" + "a".repeat(43);

    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.PAYMENT_REQUESTED,
                    "agent",
                    Map.of("memo", "Please use " + key))))
        .isInstanceOf(SecretInLedgerPayloadException.class)
        .hasMessageContaining("looks like an agent API key")
        .hasMessageNotContaining(key);
  }

  @Test
  void rejectsBearerTokensAndPrivateKeys() {
    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.RECONCILED,
                    "reconciler",
                    Map.of("note", "Bearer abc.def.ghi"))))
        .isInstanceOf(SecretInLedgerPayloadException.class)
        .hasMessageContaining("bearer token");

    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.RECONCILED,
                    "reconciler",
                    Map.of("note", "-----BEGIN RSA PRIVATE KEY-----"))))
        .isInstanceOf(SecretInLedgerPayloadException.class)
        .hasMessageContaining("private key");
  }

  @Test
  void rejectsUnsupportedPayloadValueTypes() {
    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.RECONCILED,
                    "reconciler",
                    Map.of("when", Instant.now()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported ledger payload value");
  }

  @Test
  void allowsNullPayloadValues() {
    Map<String, Object> payload = new HashMap<>();
    payload.put("note", null);
    UUID paymentId = UUID.randomUUID();

    LedgerEvent event =
        inTransaction(() ->
            ledgerService.append(
                UUID.randomUUID(), paymentId, LedgerEventType.RECONCILED, "reconciler", payload));

    assertThat(ledgerService.eventsForPayment(paymentId).getFirst().payload())
        .containsEntry("note", null);
    assertThat(event.payload()).isUnmodifiable();
  }

  @Test
  void requiresACallerTransaction() {
    assertThatThrownBy(() ->
            ledgerService.append(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LedgerEventType.RECONCILED,
                "reconciler",
                Map.of("reason", "retry")))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void validatesActorAndRequiredArguments() {
    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LedgerEventType.RECONCILED,
                    "  ",
                    Map.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");

    assertThatThrownBy(() ->
            inTransaction(() ->
                ledgerService.append(
                    null, UUID.randomUUID(), LedgerEventType.RECONCILED, "reconciler", Map.of())))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("agentId");

    assertThatThrownBy(() -> ledgerService.eventsForPayment(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("paymentId");
  }

  @Test
  void eventToStringNeverIncludesThePayload() {
    LedgerEvent event =
        inTransaction(() ->
            ledgerService.append(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LedgerEventType.PAYMENT_REQUESTED,
                "agent",
                Map.of("memo", "Invoice inv-1", "currency", "USD")));

    assertThat(event.toString())
        .contains("PAYMENT_REQUESTED")
        .doesNotContain("Invoice inv-1")
        .doesNotContain("USD");
  }

  private LedgerEvent appendSimpleEvent() {
    return inTransaction(() ->
        ledgerService.append(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LedgerEventType.DECISION_MADE,
            "policy-engine",
            Map.of("outcome", "APPROVE")));
  }

  private long rowCountForPayment(UUID paymentId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_events WHERE payment_id = ?", Long.class, paymentId);
    return count == null ? 0 : count;
  }

  private <T> T inTransaction(Supplier<T> work) {
    return transactionTemplate.execute(status -> work.get());
  }
}
