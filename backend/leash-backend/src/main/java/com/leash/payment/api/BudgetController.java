package com.leash.payment.api;

import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.payment.app.PaymentQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent-facing live budget. Authentication is handled by the API-key filter. */
@RestController
@RequestMapping("/api/v1/budget")
class BudgetController {

  private final PaymentQueryService queries;

  BudgetController(PaymentQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  BudgetResponse current(@AuthenticationPrincipal AuthenticatedAgent agent) {
    return BudgetResponse.from(queries.currentBudget(agent.agentId()));
  }
}
