package com.leash.payment.domain;

/** The lifecycle of one human approval. Only {@link #PENDING} can be decided. */
public enum ApprovalStatus {
  PENDING,
  APPROVED,
  REJECTED
}
