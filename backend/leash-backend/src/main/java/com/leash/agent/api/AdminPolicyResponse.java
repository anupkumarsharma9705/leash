package com.leash.agent.api;

import com.leash.policy.AgentPolicy;
import java.util.List;

/** The policy representation shared by every admin agent response. */
public record AdminPolicyResponse(
    long weeklyLimitMinor, long autoApproveThresholdMinor, List<String> allowedPayees) {

  static AdminPolicyResponse from(AgentPolicy policy) {
    return new AdminPolicyResponse(
        policy.weeklyLimitMinor(),
        policy.autoApproveThresholdMinor(),
        policy.allowedPayees().stream().sorted().toList());
  }
}
