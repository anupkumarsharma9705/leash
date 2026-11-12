package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeysTest {

  @ParameterizedTest
  @ValueSource(strings = {"a", "invoice-1", "A_b.c:d", "0", "0-0_0.0:0"})
  void acceptsSupportedKeyShapes(String key) {
    assertThat(IdempotencyKeys.validate(key)).isEqualTo(key);
  }

  @Test
  void acceptsTheLongestAllowedKey() {
    String key = "a".repeat(128);

    assertThat(IdempotencyKeys.validate(key)).isEqualTo(key);
  }

  @Test
  void rejectsMissingAndBlankKeys() {
    assertThatThrownBy(() -> IdempotencyKeys.validate(null))
        .isInstanceOf(MissingIdempotencyKeyException.class);
    assertThatThrownBy(() -> IdempotencyKeys.validate(""))
        .isInstanceOf(MissingIdempotencyKeyException.class);
    assertThatThrownBy(() -> IdempotencyKeys.validate("   "))
        .isInstanceOf(MissingIdempotencyKeyException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "with space",
        "slash/",
        "semi;colon",
        "plus+",
        "quote\"",
        "back\\slash",
        "unicode-\u00e9",
        "line\nbreak"
      })
  void rejectsUnsupportedCharacters(String key) {
    assertThatThrownBy(() -> IdempotencyKeys.validate(key))
        .isInstanceOf(InvalidIdempotencyKeyException.class)
        .hasMessageContaining("1-128");
  }

  @Test
  void rejectsAnOverlongKey() {
    assertThatThrownBy(() -> IdempotencyKeys.validate("a".repeat(129)))
        .isInstanceOf(InvalidIdempotencyKeyException.class);
  }
}
