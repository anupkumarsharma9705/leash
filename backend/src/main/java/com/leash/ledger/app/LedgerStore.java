package com.leash.ledger.app;

import com.leash.ledger.domain.LedgerEvent;
import java.util.List;
import java.util.UUID;

/** Persistence boundary for the append-only ledger. There is deliberately no update or delete. */
public interface LedgerStore {

  /** Inserts the event and returns it with the generated id. */
  LedgerEvent insert(LedgerEvent event);

  /** Returns the timeline for one payment, oldest first. */
  List<LedgerEvent> findByPaymentId(UUID paymentId);
}
