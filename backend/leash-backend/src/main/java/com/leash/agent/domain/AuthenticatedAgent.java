package com.leash.agent.domain;

import java.util.UUID;

/** The identity established by a valid agent API key. */
public record AuthenticatedAgent(UUID agentId, String name) {

  public AuthenticatedAgent {
    if (agentId == null) {
      throw new IllegalArgumentException("agentId is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
  }
}
