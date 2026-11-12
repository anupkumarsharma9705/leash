package com.leash.paypal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SandboxGuardTest {

  @Test
  void acceptsSandboxHost() {
    assertThatCode(() -> SandboxGuard.verify("https://api-m.sandbox.paypal.com", false))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsLiveHost() {
    assertThatThrownBy(() -> SandboxGuard.verify("https://api-m.paypal.com", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sandbox");
  }

  @Test
  void rejectsHttpEvenOnSandboxHost() {
    assertThatThrownBy(() -> SandboxGuard.verify("http://api-m.sandbox.paypal.com", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sandbox");
  }

  @Test
  void rejectsLookalikeHost() {
    assertThatThrownBy(
            () -> SandboxGuard.verify("https://api-m.sandbox.paypal.com.evil.example", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sandbox");
  }

  @Test
  void rejectsGarbageUrl() {
    assertThatThrownBy(() -> SandboxGuard.verify("not a url", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid URL");
  }

  @Test
  void allowsLiveOnlyWhenExplicitlyEnabled() {
    assertThatCode(() -> SandboxGuard.verify("https://api-m.paypal.com", true))
        .doesNotThrowAnyException();
  }
}
