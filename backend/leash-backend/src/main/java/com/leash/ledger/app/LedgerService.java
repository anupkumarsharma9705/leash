package com.leash.ledger.app;

import com.leash.ledger.domain.LedgerEvent;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.ledger.domain.LedgerPayloadGuard;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes ledger events inside the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} is intentional: a business change and its ledger fact must
 * commit or roll back together. Calling this service without a transaction is a programming error.
 */
@Service
public class LedgerService {

  private final LedgerStore store;
  private final Clock clock;

  public LedgerService(LedgerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * Appends one event to the ledger.
   *
   * @throws SecretInLedgerPayloadException when the payload contains a secret-bearing field/value
   * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a
   *     transaction
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public LedgerEvent append(
      UUID agentId,
      UUID paymentId,
      LedgerEventType eventType,
      String actor,
      Map<String, Object> payload) {
    Objects.requireNonNull(agentId, "agentId is required");
    Objects.requireNonNull(eventType, "eventType is required");
    Objects.requireNonNull(payload, "payload is required");
    LedgerPayloadGuard.validate(payload);

    LedgerEvent event =
        new LedgerEvent(null, agentId, paymentId, eventType, actor, payload, clock.instant());
    return store.insert(event);
  }

  /** Reads the timeline for one payment, oldest first. */
  @Transactional(readOnly = true)
  public java.util.List<LedgerEvent> eventsForPayment(UUID paymentId) {
    Objects.requireNonNull(paymentId, "paymentId is required");
    return store.findByPaymentId(paymentId);
  }
}
