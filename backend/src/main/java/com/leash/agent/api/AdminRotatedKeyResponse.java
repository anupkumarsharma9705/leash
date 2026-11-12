package com.leash.agent.api;

import com.leash.agent.domain.RotatedApiKey;
import java.util.UUID;

/**
 * The rotate-key response. The plaintext key is available only here; {@code toString} redacts it.
 */
public record AdminRotatedKeyResponse(UUID agentId, String apiKey) {

  static AdminRotatedKeyResponse from(RotatedApiKey rotated) {
    return new AdminRotatedKeyResponse(rotated.agentId(), rotated.apiKey());
  }

  @Override
  public String toString() {
    return "AdminRotatedKeyResponse[agentId=" + agentId + ", apiKey=***]";
  }
}
