package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentFingerprintTest {

  @Test
  void producesLowercaseHexSha256() {
    assertThat(fingerprint("alice@example.com", 1200, "USD", "Invoice inv-1"))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void isStableForEquivalentNormalizedRequests() {
    String normalized = fingerprint("alice@example.com", 1200, "USD", "Invoice inv-1");
    String noisy = fingerprint("  Alice@Example.COM  ", 1200, " usd ", "  Invoice inv-1  ");

    assertThat(noisy).isEqualTo(normalized);
  }

  @Test
  void changesWhenAnyCanonicalFieldChanges() {
    String base = fingerprint("alice@example.com", 1200, "USD", "Invoice inv-1");

    assertThat(fingerprint("bob@example.com", 1200, "USD", "Invoice inv-1")).isNotEqualTo(base);
    assertThat(fingerprint("alice@example.com", 1201, "USD", "Invoice inv-1")).isNotEqualTo(base);
    assertThat(fingerprint("alice@example.com", 1200, "EUR", "Invoice inv-1")).isNotEqualTo(base);
    assertThat(fingerprint("alice@example.com", 1200, "USD", "Invoice inv-2")).isNotEqualTo(base);
    assertThat(fingerprint("alice@example.com", 1200, "USD", "")).isNotEqualTo(base);
  }

  @Test
  void lengthPrefixesPreventFieldBoundaryCollisions() {
    // Without length prefixes both requests canonicalize to "a12USDx": email "a" + amount "12",
    // or email "a1" + amount "2".
    String first = fingerprint("a", 12, "USD", "x");
    String second = fingerprint("a1", 2, "USD", "x");

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isDeterministicAcrossInstances() {
    assertThat(new PaymentCommand("alice@example.com", 1200, "USD", "memo").fingerprint())
        .isEqualTo(new PaymentCommand("alice@example.com", 1200, "USD", "memo").fingerprint());
  }

  private static String fingerprint(String payee, long amount, String currency, String memo) {
    return new PaymentCommand(payee, amount, currency, memo).fingerprint();
  }
}
