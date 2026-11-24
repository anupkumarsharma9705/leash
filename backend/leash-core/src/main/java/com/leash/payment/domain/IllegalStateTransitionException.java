package com.leash.payment.domain;

/** Raised when code attempts a payment state change that the state machine forbids. */
public class IllegalStateTransitionException extends RuntimeException {

  private final PaymentState from;
  private final PaymentState to;

  public IllegalStateTransitionException(PaymentState from, PaymentState to) {
    super("Illegal payment state transition from " + from + " to " + to);
    this.from = from;
    this.to = to;
  }

  public PaymentState from() {
    return from;
  }

  public PaymentState to() {
    return to;
  }
}
