package com.leash.agent.app;

/** Weekly budget aggregates for one agent. */
public record AgentBudget(long spentMinor, long reservedMinor) {

  public AgentBudget {
    if (spentMinor < 0) {
      throw new IllegalArgumentException("spentMinor must not be negative");
    }
    if (reservedMinor < 0) {
      throw new IllegalArgumentException("reservedMinor must not be negative");
    }
  }

  public static AgentBudget empty() {
    return new AgentBudget(0, 0);
  }
}
