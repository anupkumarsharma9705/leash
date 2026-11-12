package com.leash.agent.app;

import com.leash.agent.domain.Agent;

/** An agent plus its live current-week spend, used by the admin list. */
public record AgentSummary(Agent agent, long spentMinor, long reservedMinor) {

  /** Remaining budget, saturated at zero like every other budget view. */
  public long remainingMinor() {
    long committed = spentMinor + reservedMinor;
    long limit = agent.policy().weeklyLimitMinor();
    if (committed < 0) {
      return 0;
    }
    return committed >= limit ? 0 : limit - committed;
  }
}
