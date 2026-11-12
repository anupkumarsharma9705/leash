package com.leash.demoagent;

import java.util.Objects;
import java.util.UUID;

/**
 * The demo agent's id, display name, and current plaintext API key.
 *
 * <p>The key lives only in memory for the lifetime of the process. It is never written to the
 * database, a log, or an admin response. {@link #toString()} is redacted.
 */
public final class DemoAgentIdentity {

  private final UUID agentId;
  private final String agentName;
  private final String apiKey;

  DemoAgentIdentity(UUID agentId, String agentName, String apiKey) {
    this.agentId = Objects.requireNonNull(agentId, "agentId is required");
    this.agentName = Objects.requireNonNull(agentName, "agentName is required");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey is required");
    }
    this.apiKey = apiKey;
  }

  public UUID agentId() {
    return agentId;
  }

  public String agentName() {
    return agentName;
  }

  String apiKey() {
    return apiKey;
  }

  @Override
  public String toString() {
    return "DemoAgentIdentity[agentId=" + agentId + ", agentName=" + agentName + ", apiKey=***]";
  }
}
