package com.leash.payment.domain;

/** Raised when an idempotency key is replayed with a different request body. */
public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("This Idempotency-Key was already used with a different payment request");
  }
}
