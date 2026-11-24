package com.leash.demoagent;

import java.util.List;
import java.util.UUID;

/** A payment decision as seen by the demo agent over the public HTTP API. */
public record DemoPayment(
    UUID paymentId,
    String state,
    String outcome,
    List<DemoReason> reasons,
    DemoBudget budget,
    boolean replayed) {

  public DemoPayment {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  /** One reason code attached to a decision. */
  public record DemoReason(String code, String message) {}
}
