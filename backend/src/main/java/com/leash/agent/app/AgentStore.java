package com.leash.agent.app;

import com.leash.agent.domain.Agent;
import com.leash.policy.AgentPolicy;
import com.leash.policy.AgentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for agents, policies, and API keys. */
public interface AgentStore {

  void insertAgent(Agent agent);

  void insertApiKey(ApiKeyRecord apiKey);

  /** Returns keys sharing an identification prefix; callers compare hashes in constant time. */
  List<ApiKeyCandidate> findApiKeyCandidates(String prefix);

  void markApiKeyUsed(UUID apiKeyId, Instant usedAt);

  /** Returns an agent with its current status and policy, without locking. */
  Optional<Agent> findAgent(UUID agentId);

  /** Returns every agent with its policy, oldest first. One query per table, never per row. */
  List<Agent> listAgents();

  /**
   * Locks the agent row for the duration of the caller's transaction and returns the agent with its
   * current status and policy. Must be called inside a transaction.
   */
  Optional<Agent> lockAgent(UUID agentId);

  /** Replaces the policy and allow-list. The agent row must already be locked. */
  void updatePolicy(UUID agentId, AgentPolicy policy, Instant updatedAt);

  /** Changes the agent status. The agent row must already be locked. */
  void updateStatus(UUID agentId, AgentStatus status);

  /** Revokes every key that is not already revoked and returns how many were revoked. */
  int revokeActiveApiKeys(UUID agentId, Instant revokedAt);
}
