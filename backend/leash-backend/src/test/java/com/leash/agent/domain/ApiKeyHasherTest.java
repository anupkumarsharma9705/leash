package com.leash.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

  private final ApiKeyHasher hasher = new ApiKeyHasher();

  @Test
  void hashesWithSha256Hex() {
    assertThat(hasher.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void matchesOnlyTheSamePlaintext() {
    String hash = hasher.hash("lsh_example-key");

    assertThat(hasher.matches("lsh_example-key", hash)).isTrue();
    assertThat(hasher.matches("lsh_example-kez", hash)).isFalse();
    assertThat(hasher.matches(null, hash)).isFalse();
    assertThat(hasher.matches("lsh_example-key", null)).isFalse();
    assertThat(hasher.matches("lsh_example-key", "not-hex")).isFalse();
    assertThat(hasher.matches("lsh_example-key", "")).isFalse();
  }

  @Test
  void hashIsNotThePlaintextAndIsSixtyFourLowercaseHexCharacters() {
    String key = "lsh_example-key";

    assertThat(hasher.hash(key)).isNotEqualTo(key).matches("[0-9a-f]{64}");
  }
}
