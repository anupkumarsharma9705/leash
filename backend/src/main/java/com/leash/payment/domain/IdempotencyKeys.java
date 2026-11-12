package com.leash.payment.domain;

import java.util.regex.Pattern;

/** Validation for client-supplied idempotency keys. */
public final class IdempotencyKeys {

  private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  private IdempotencyKeys() {}

  /**
   * Validates and returns the key unchanged.
   *
   * @throws MissingIdempotencyKeyException when the key is null or blank
   * @throws InvalidIdempotencyKeyException when the key has an unsupported shape
   */
  public static String validate(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new MissingIdempotencyKeyException();
    }
    if (!SHAPE.matcher(idempotencyKey).matches()) {
      throw new InvalidIdempotencyKeyException();
    }
    return idempotencyKey;
  }
}
