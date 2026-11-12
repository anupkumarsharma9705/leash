package com.leash.demoagent;

import java.util.List;
import java.util.UUID;

/** One invoice scene and the outcome Leash returned to the demo agent. */
public record DemoSceneResult(
    String invoiceId,
    String label,
    String payeeEmail,
    long amountMinor,
    String currency,
    String memo,
    UUID paymentId,
    String state,
    String outcome,
    List<DemoPayment.DemoReason> reasons,
    boolean replayed,
    String errorCode,
    String errorMessage) {

  public DemoSceneResult {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  static DemoSceneResult from(DemoInvoice invoice, DemoPayment payment) {
    return new DemoSceneResult(
        invoice.id(),
        invoice.label(),
        invoice.payeeEmail(),
        invoice.amountMinor(),
        invoice.currency(),
        invoice.memo(),
        payment.paymentId(),
        payment.state(),
        payment.outcome(),
        payment.reasons(),
        payment.replayed(),
        null,
        null);
  }

  static DemoSceneResult from(DemoInvoice invoice, DemoClientException exception) {
    return new DemoSceneResult(
        invoice.id(),
        invoice.label(),
        invoice.payeeEmail(),
        invoice.amountMinor(),
        invoice.currency(),
        invoice.memo(),
        null,
        null,
        null,
        List.of(),
        false,
        exception.code(),
        exception.getMessage());
  }

  /** True when Leash returned a decision, including a denial. */
  public boolean decisionReceived() {
    return errorCode == null;
  }
}
