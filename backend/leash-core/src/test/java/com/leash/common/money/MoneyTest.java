package com.leash.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void acceptsPositiveAmount() {
    Money money = new Money(1200, SupportedCurrency.USD);

    assertThat(money.amountMinor()).isEqualTo(1200);
    assertThat(money.currency()).isEqualTo(SupportedCurrency.USD);
  }

  @Test
  void rejectsZeroAmount() {
    assertThatThrownBy(() -> new Money(0, SupportedCurrency.USD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be positive");
  }

  @Test
  void rejectsNegativeAmount() {
    assertThatThrownBy(() -> new Money(-1, SupportedCurrency.USD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be positive");
  }

  @Test
  void rejectsMissingCurrency() {
    assertThatThrownBy(() -> new Money(100, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("currency is required");
  }

  @Test
  void capBoundaryIsInclusive() {
    Money money = Money.validated(100000, SupportedCurrency.USD, 100000);

    assertThat(money.amountMinor()).isEqualTo(100000);
  }

  @Test
  void rejectsAmountOneMinorUnitAboveCap() {
    assertThatThrownBy(() -> Money.validated(100001, SupportedCurrency.USD, 100000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hard cap");
  }

  @Test
  void rejectsNonPositiveCap() {
    assertThatThrownBy(() -> Money.validated(100, SupportedCurrency.USD, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max amount must be positive");
  }
}
