package com.leash.common.money;

/**
 * A positive monetary amount in minor units.
 *
 * <p>Money is never represented as a floating-point value. Conversion to a decimal string happens
 * only at the PayPal adapter boundary.
 */
public record Money(long amountMinor, SupportedCurrency currency) {

  public Money {
    if (currency == null) {
      throw new IllegalArgumentException("currency is required");
    }
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  /**
   * Creates an amount and enforces the configured hard cap.
   *
   * @throws IllegalArgumentException when the amount is not positive or exceeds the cap
   */
  public static Money validated(long amountMinor, SupportedCurrency currency, long maxAmountMinor) {
    if (maxAmountMinor <= 0) {
      throw new IllegalArgumentException("max amount must be positive");
    }
    Money money = new Money(amountMinor, currency);
    if (amountMinor > maxAmountMinor) {
      throw new IllegalArgumentException(
          "amount exceeds hard cap of " + maxAmountMinor + " minor units");
    }
    return money;
  }
}
