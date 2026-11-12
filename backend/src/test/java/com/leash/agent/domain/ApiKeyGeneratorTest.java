package com.leash.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

  private final ApiKeyGenerator generator = new ApiKeyGenerator(new SecureRandom());

  @Test
  void generatesKeysInTheDocumentedFormat() {
    String key = generator.generate();

    assertThat(key).startsWith("lsh_").hasSize(47);
    assertThat(key.substring(4)).matches("[A-Za-z0-9_-]{43}");
    assertThat(generator.isWellFormed(key)).isTrue();
  }

  @Test
  void generatesDistinctKeys() {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      assertThat(keys.add(generator.generate())).isTrue();
    }
  }

  @Test
  void prefixIsTheFirstEightCharacters() {
    String key = generator.generate();

    assertThat(generator.prefix(key)).isEqualTo(key.substring(0, 8)).startsWith("lsh_");
  }

  @Test
  void rejectsMalformedKeys() {
    String valid = generator.generate();

    assertThat(generator.isWellFormed(null)).isFalse();
    assertThat(generator.isWellFormed("")).isFalse();
    assertThat(generator.isWellFormed("lsh_")).isFalse();
    assertThat(generator.isWellFormed("lsh_" + "a".repeat(42))).isFalse();
    assertThat(generator.isWellFormed(valid + "x")).isFalse();
    assertThat(generator.isWellFormed(valid.substring(0, valid.length() - 1) + "+")).isFalse();
    assertThat(generator.isWellFormed("abc_" + valid.substring(4))).isFalse();
    assertThat(generator.isWellFormed(" " + valid)).isFalse();
  }

  @Test
  void prefixRejectsMalformedKeys() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> generator.prefix("not-a-key"))
        .withMessageContaining("format");
  }
}
