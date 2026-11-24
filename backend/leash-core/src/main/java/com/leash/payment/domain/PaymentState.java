package com.leash.payment.domain;

/** The only states a payment request can be in. Transitions are owned by PaymentStateMachine. */
public enum PaymentState {
  PENDING_APPROVAL,
  APPROVED,
  SUBMITTED,
  SUCCEEDED,
  FAILED,
  REJECTED,
  DENIED
}
