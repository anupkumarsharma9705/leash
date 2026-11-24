package com.leash.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayPalAmountFormatterTest {

  @ParameterizedTest
  @CsvSource({"1,0.01", "12,0.12", "1200,12.00", "100000,1000.00"})
  void formatsMinorUnitsAsDecimalString(long amountMinor, String expected) {
    assertThat(PayPalAmountFormatter.toDecimalString(amountMinor)).isEqualTo(expected);
  }

  @Test
  void rejectsZero() {
    assertThatThrownBy(() -> PayPalAmountFormatter.toDecimalString(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be positive");
  }

  @Test
  void rejectsNegative() {
    assertThatThrownBy(() -> PayPalAmountFormatter.toDecimalString(-100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be positive");
  }
}
