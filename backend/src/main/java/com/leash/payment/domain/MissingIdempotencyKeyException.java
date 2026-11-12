package com.leash.payment.domain;

/** Raised when the required {@code Idempotency-Key} header is absent or blank. */
public class MissingIdempotencyKeyException extends RuntimeException {

  public MissingIdempotencyKeyException() {
    super("The Idempotency-Key header is required");
  }
}
