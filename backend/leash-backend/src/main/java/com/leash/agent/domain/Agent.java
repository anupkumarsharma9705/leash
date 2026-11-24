package com.leash.agent.domain;

import com.leash.policy.AgentPolicy;
import com.leash.policy.AgentStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A registered AI agent and its spending policy. */
public record Agent(
    UUID id, String name, AgentStatus status, AgentPolicy policy, Instant createdAt) {

  public Agent {
    Objects.requireNonNull(id, "id is required");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    name = name.trim();
    if (name.length() > 100) {
      throw new IllegalArgumentException("name must be at most 100 characters");
    }
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(policy, "policy is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
  }
}
