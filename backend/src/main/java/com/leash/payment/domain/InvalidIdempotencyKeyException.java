package com.leash.payment.domain;

/** Raised when the {@code Idempotency-Key} header is present but not in the accepted format. */
public class InvalidIdempotencyKeyException extends RuntimeException {

  public InvalidIdempotencyKeyException() {
    super(
        "Idempotency-Key must be 1-128 characters from A-Z, a-z, 0-9, dot, underscore, colon, or hyphen");
  }
}
