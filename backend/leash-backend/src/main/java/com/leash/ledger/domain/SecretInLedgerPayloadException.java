package com.leash.ledger.domain;

/** Raised when a ledger payload contains a value or field that must never be persisted. */
public class SecretInLedgerPayloadException extends IllegalArgumentException {

  public SecretInLedgerPayloadException(String message) {
    super(message);
  }
}
