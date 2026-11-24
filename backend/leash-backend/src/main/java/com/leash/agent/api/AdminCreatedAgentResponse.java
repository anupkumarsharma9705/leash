package com.leash.agent.api;

import com.leash.agent.domain.Agent;
import com.leash.agent.domain.CreatedAgent;
import java.time.Instant;
import java.util.UUID;

/**
 * The create-agent response. {@code apiKey} is the only time the plaintext key is returned from
 * creation; {@code toString} is redacted.
 */
public record AdminCreatedAgentResponse(
    UUID agentId,
    String name,
    String status,
    Instant createdAt,
    AdminPolicyResponse policy,
    AdminBudgetResponse budget,
    String apiKey) {

  static AdminCreatedAgentResponse from(CreatedAgent created) {
    Agent agent = created.agent();
    return new AdminCreatedAgentResponse(
        agent.id(),
        agent.name(),
        agent.status().name(),
        agent.createdAt(),
        AdminPolicyResponse.from(agent.policy()),
        AdminBudgetResponse.of(agent.policy().weeklyLimitMinor(), 0, 0),
        created.apiKey());
  }

  @Override
  public String toString() {
    return "AdminCreatedAgentResponse[agentId=" + agentId + ", apiKey=***]";
  }
}
