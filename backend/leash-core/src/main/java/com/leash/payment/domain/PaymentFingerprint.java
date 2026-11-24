package com.leash.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical SHA-256 fingerprint of a payment request.
 *
 * <p>The field order is fixed: {@code payeeEmail}, {@code amountMinor}, {@code currency}, {@code
 * memo}. Each field is length-prefixed, so no combination of field contents can collide with a
 * different split of the same bytes.
 */
public final class PaymentFingerprint {

  private static final HexFormat HEX = HexFormat.of();

  private PaymentFingerprint() {}

  public static String compute(PaymentCommand command) {
    String canonical =
        field(command.payeeEmail())
            + field(Long.toString(command.amountMinor()))
            + field(command.currency())
            + field(command.memo());
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String field(String value) {
    return value.length() + ":" + value;
  }
}
