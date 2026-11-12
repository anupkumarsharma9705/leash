package com.leash.paypal;

import java.math.BigDecimal;

/**
 * Converts minor units to PayPal's decimal amount string.
 *
 * <p>Only the PayPal adapter may call this; the domain never deals in decimal strings.
 */
public final class PayPalAmountFormatter {

  private PayPalAmountFormatter() {}

  public static String toDecimalString(long amountMinor) {
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    return BigDecimal.valueOf(amountMinor).movePointLeft(2).toPlainString();
  }
}
