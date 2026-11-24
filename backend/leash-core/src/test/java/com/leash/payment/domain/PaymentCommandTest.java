package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaymentCommandTest {

  @Test
  void normalizesEmailCurrencyAndMemo() {
    PaymentCommand command =
        new PaymentCommand("  Alice@Example.COM  ", 1200, " usd ", "  Invoice inv-1  ");

    assertThat(command.payeeEmail()).isEqualTo("alice@example.com");
    assertThat(command.amountMinor()).isEqualTo(1200);
    assertThat(command.currency()).isEqualTo("USD");
    assertThat(command.memo()).isEqualTo("Invoice inv-1");
  }

  @Test
  void turnsANullMemoIntoAnEmptyString() {
    assertThat(new PaymentCommand("alice@example.com", 1, "USD", null).memo()).isEmpty();
  }

  @Test
  void acceptsMinorUnitsAndThreeLetterCurrencies() {
    assertThat(new PaymentCommand("a@b.co", 1, "eur", "").currency()).isEqualTo("EUR");
  }

  @Test
  void rejectsMissingOrBlankPayee() {
    assertThatThrownBy(() -> new PaymentCommand(null, 1200, "USD", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payeeEmail");
    assertThatThrownBy(() -> new PaymentCommand("   ", 1200, "USD", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payeeEmail");
  }

  @Test
  void rejectsAnOverlongPayee() {
    String payee = "a".repeat(243) + "@example.com";

    assertThat(payee).hasSize(255);
    assertThatThrownBy(() -> new PaymentCommand(payee, 1200, "USD", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("254");
  }

  @Test
  void acceptsTheLongestAllowedPayee() {
    String payee = "a".repeat(242) + "@example.com";

    assertThat(payee).hasSize(254);
    assertThat(new PaymentCommand(payee, 1200, "USD", "").payeeEmail()).isEqualTo(payee);
  }

  @Test
  void rejectsNonPositiveAmounts() {
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 0, "USD", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", -1, "USD", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void rejectsMissingOrMalformedCurrency() {
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 1200, null, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 1200, "  ", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 1200, "US", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("three-letter");
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 1200, "US1", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("three-letter");
  }

  @Test
  void rejectsAnOverlongMemo() {
    assertThatThrownBy(() -> new PaymentCommand("alice@example.com", 1200, "USD", "x".repeat(256)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("255");
  }

  @Test
  void acceptsTheLongestAllowedMemo() {
    String memo = "x".repeat(255);

    assertThat(new PaymentCommand("alice@example.com", 1200, "USD", memo).memo()).isEqualTo(memo);
  }
}
