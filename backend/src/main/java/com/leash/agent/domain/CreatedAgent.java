package com.leash.agent.domain;

import java.util.Objects;

/**
 * The result of registering an agent.
 *
 * <p>{@code apiKey} is the only time the plaintext key is available. Its {@code toString} is
 * redacted so accidental logging cannot leak it.
 */
public record CreatedAgent(Agent agent, String apiKey) {

  public CreatedAgent {
    Objects.requireNonNull(agent, "agent is required");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey is required");
    }
  }

  @Override
  public String toString() {
    return "CreatedAgent[agent=" + agent + ", apiKey=***]";
  }
}
