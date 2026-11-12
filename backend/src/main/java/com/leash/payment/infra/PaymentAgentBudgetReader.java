package com.leash.payment.infra;

import com.leash.agent.app.AgentBudget;
import com.leash.agent.app.AgentBudgetReader;
import com.leash.payment.app.PaymentStore;
import com.leash.payment.domain.BudgetTotals;
import com.leash.policy.BudgetPeriod;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements the agent feature's budget read port over the payment feature's own store.
 *
 * <p>The grouped query keeps the admin agent list to a fixed number of SQL statements regardless of
 * how many agents exist.
 */
@Component
public class PaymentAgentBudgetReader implements AgentBudgetReader {

  private final PaymentStore paymentStore;

  public PaymentAgentBudgetReader(PaymentStore paymentStore) {
    this.paymentStore = paymentStore;
  }

  @Override
  public Map<UUID, AgentBudget> totalsFor(BudgetPeriod period) {
    Map<UUID, BudgetTotals> totals =
        paymentStore.budgetTotalsByAgent(period.startInclusive(), period.endExclusive());
    Map<UUID, AgentBudget> budgets = new HashMap<>(totals.size());
    totals.forEach((agentId, total) ->
        budgets.put(agentId, new AgentBudget(total.spentMinor(), total.reservedMinor())));
    return Map.copyOf(budgets);
  }
}
