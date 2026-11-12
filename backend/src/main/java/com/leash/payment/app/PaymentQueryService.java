package com.leash.payment.app;

import com.leash.agent.app.AgentService;
import com.leash.agent.domain.Agent;
import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.BudgetTotals;
import com.leash.payment.domain.PaymentNotFoundException;
import com.leash.payment.domain.PaymentRecord;
import com.leash.policy.BudgetPeriod;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side queries for the agent API: one owned payment and the live weekly budget. */
@Service
public class PaymentQueryService {

  private final PaymentStore paymentStore;
  private final AgentService agentService;
  private final Clock clock;

  public PaymentQueryService(PaymentStore paymentStore, AgentService agentService, Clock clock) {
    this.paymentStore = paymentStore;
    this.agentService = agentService;
    this.clock = clock;
  }

  /**
   * Returns a payment only when the authenticated agent owns it.
   *
   * @throws PaymentNotFoundException when the payment is missing or belongs to another agent
   */
  @Transactional(readOnly = true)
  public PaymentRecord findOwnedPayment(UUID agentId, UUID paymentId) {
    Objects.requireNonNull(agentId, "agentId is required");
    Objects.requireNonNull(paymentId, "paymentId is required");
    return paymentStore
        .findById(paymentId)
        .filter(record -> record.agentId().equals(agentId))
        .orElseThrow(PaymentNotFoundException::new);
  }

  /**
   * Returns live budget aggregates for the current UTC week.
   *
   * <p>Unlike the {@code budget} block on a payment response, this is not a decision-time snapshot:
   * it is computed from the committed states at call time.
   */
  @Transactional(readOnly = true)
  public BudgetSnapshot currentBudget(UUID agentId) {
    Objects.requireNonNull(agentId, "agentId is required");
    Agent agent = agentService.findAgent(agentId);
    BudgetPeriod period = BudgetPeriod.current(clock);
    BudgetTotals totals =
        paymentStore.budgetTotals(agentId, period.startInclusive(), period.endExclusive());
    return new BudgetSnapshot(
        agent.policy().weeklyLimitMinor(), totals.spentMinor(), totals.reservedMinor());
  }
}
