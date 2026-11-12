package com.leash.agent.app;

import com.leash.policy.BudgetPeriod;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side port for current-week spend.
 *
 * <p>Implemented by the payment feature so the agent feature can list budgets without reaching into
 * another feature's repository or issuing one query per agent.
 */
public interface AgentBudgetReader {

  /** Returns totals for every agent that has payments in the period. Missing agents spent zero. */
  Map<UUID, AgentBudget> totalsFor(BudgetPeriod period);
}
