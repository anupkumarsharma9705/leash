package com.leash.demoagent;

/** The two tools the demo agent is allowed to call, expressed only over HTTP. */
public interface DemoLeashClient {

  /** Reads the current-week budget for the demo agent. */
  DemoBudget checkBudget();

  /**
   * Requests one invoice payment using {@code Idempotency-Key: invoice-<id>}.
   *
   * @throws DemoClientException when Leash rejects the call or is unreachable
   */
  DemoPayment payInvoice(DemoInvoice invoice);
}
