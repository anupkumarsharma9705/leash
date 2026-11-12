package com.leash.agent.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The result of rotating an agent API key.
 *
 * <p>{@code apiKey} is the only time the new plaintext key is available. Its {@code toString} is
 * redacted so accidental logging cannot leak it.
 */
public record RotatedApiKey(UUID agentId, String apiKey) {

  public RotatedApiKey {
    Objects.requireNonNull(agentId, "agentId is required");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey is required");
    }
  }

  @Override
  public String toString() {
    return "RotatedApiKey[agentId=" + agentId + ", apiKey=***]";
  }
}
