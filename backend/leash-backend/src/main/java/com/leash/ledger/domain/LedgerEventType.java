package com.leash.ledger.domain;

/** Stable business-fact types written to the append-only ledger. */
public enum LedgerEventType {
  PAYMENT_REQUESTED,
  DECISION_MADE,
  APPROVAL_CREATED,
  APPROVAL_DECIDED,
  PAYPAL_SUBMITTED,
  PAYPAL_STATUS_CHANGED,
  RECONCILED,
  POLICY_CHANGED,
  AGENT_STATUS_CHANGED,
  API_KEY_ROTATED
}
