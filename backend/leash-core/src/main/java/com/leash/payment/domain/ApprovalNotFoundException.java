package com.leash.payment.domain;

import java.util.UUID;

/** Raised when an administrative approval route names an approval that does not exist. */
public class ApprovalNotFoundException extends RuntimeException {

  private final UUID approvalId;

  public ApprovalNotFoundException(UUID approvalId) {
    super("Approval not found: " + approvalId);
    this.approvalId = approvalId;
  }

  public UUID approvalId() {
    return approvalId;
  }
}
