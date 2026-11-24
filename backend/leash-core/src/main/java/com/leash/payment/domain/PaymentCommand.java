package com.leash.payment.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * One validated payment request.
 *
 * <p>The payee email is trimmed and lower-cased; the currency is trimmed and upper-cased. These
 * normalizations are part of the idempotency fingerprint.
 */
public record PaymentCommand(String payeeEmail, long amountMinor, String currency, String memo) {

  public PaymentCommand {
    if (payeeEmail == null || payeeEmail.isBlank()) {
      throw new IllegalArgumentException("payeeEmail is required");
    }
    payeeEmail = payeeEmail.trim().toLowerCase(Locale.ROOT);
    if (payeeEmail.length() > 254) {
      throw new IllegalArgumentException("payeeEmail must be at most 254 characters");
    }
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency is required");
    }
    currency = currency.trim().toUpperCase(Locale.ROOT);
    if (!currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("currency must be a three-letter code");
    }
    memo = memo == null ? "" : memo.strip();
    if (memo.length() > 255) {
      throw new IllegalArgumentException("memo must be at most 255 characters");
    }
    Objects.requireNonNull(payeeEmail, "payeeEmail is required");
  }

  /** The canonical SHA-256 fingerprint used for idempotency comparisons. */
  public String fingerprint() {
    return PaymentFingerprint.compute(this);
  }
}
