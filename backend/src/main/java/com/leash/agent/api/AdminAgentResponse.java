package com.leash.agent.api;

import com.leash.agent.domain.Agent;
import java.time.Instant;
import java.util.UUID;

/** An agent as the admin console sees it. Never contains an API key. */
public record AdminAgentResponse(
    UUID agentId,
    String name,
    String status,
    Instant createdAt,
    AdminPolicyResponse policy,
    AdminBudgetResponse budget) {

  static AdminAgentResponse from(Agent agent, long spentMinor, long reservedMinor) {
    return new AdminAgentResponse(
        agent.id(),
        agent.name(),
        agent.status().name(),
        agent.createdAt(),
        AdminPolicyResponse.from(agent.policy()),
        AdminBudgetResponse.of(agent.policy().weeklyLimitMinor(), spentMinor, reservedMinor));
  }
}
