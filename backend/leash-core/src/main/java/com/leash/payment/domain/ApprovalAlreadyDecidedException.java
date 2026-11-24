package com.leash.payment.domain;

import java.util.UUID;

/** Raised when a decision would reverse an approval that another decision already won. */
public class ApprovalAlreadyDecidedException extends RuntimeException {

  private final UUID approvalId;

  public ApprovalAlreadyDecidedException(UUID approvalId) {
    super("Approval has already been decided: " + approvalId);
    this.approvalId = approvalId;
  }

  public UUID approvalId() {
    return approvalId;
  }
}
