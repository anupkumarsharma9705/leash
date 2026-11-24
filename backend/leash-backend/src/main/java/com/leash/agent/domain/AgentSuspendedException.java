package com.leash.agent.domain;

import java.util.UUID;

/** Raised when a valid key belongs to a suspended agent. */
public class AgentSuspendedException extends RuntimeException {

  private final UUID agentId;

  public AgentSuspendedException(UUID agentId) {
    super("Agent is suspended");
    this.agentId = agentId;
  }

  public UUID agentId() {
    return agentId;
  }
}
