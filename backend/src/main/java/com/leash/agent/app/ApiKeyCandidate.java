package com.leash.agent.app;

import com.leash.policy.AgentStatus;
import java.time.Instant;
import java.util.UUID;

/** An API-key candidate joined with its owning agent, used during authentication. */
public record ApiKeyCandidate(
    UUID apiKeyId,
    UUID agentId,
    String agentName,
    AgentStatus agentStatus,
    String keyHash,
    Instant revokedAt) {}
