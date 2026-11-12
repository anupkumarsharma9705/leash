package com.leash.agent.domain;

import java.util.UUID;

/** Raised when an authenticated agent no longer exists. */
public class AgentNotFoundException extends RuntimeException {

  private final UUID agentId;

  public AgentNotFoundException(UUID agentId) {
    super("Agent not found");
    this.agentId = agentId;
  }

  public UUID agentId() {
    return agentId;
  }
}
