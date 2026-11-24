package com.leash.agent.app;

import java.time.Instant;
import java.util.UUID;

/** A persisted API key row. The plaintext key is never part of this record. */
public record ApiKeyRecord(
    UUID id, UUID agentId, String keyHash, String prefix, Instant createdAt, Instant revokedAt) {}
