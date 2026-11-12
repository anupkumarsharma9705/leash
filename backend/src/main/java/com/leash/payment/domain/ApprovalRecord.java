package com.leash.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One persisted human decision record for an escalated payment.
 *
 * <p>A pending approval carries no decision. A decided approval must name who decided it and when,
 * matching the database constraint that keeps half-decided rows out of the table.
 */
public record ApprovalRecord(
    UUID id,
    UUID paymentId,
    ApprovalStatus status,
    Instant requestedAt,
    Instant decidedAt,
    String decidedBy,
    String note,
    long version) {

  public ApprovalRecord {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(paymentId, "paymentId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(requestedAt, "requestedAt is required");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    if (note != null && note.length() > 500) {
      throw new IllegalArgumentException("note must be at most 500 characters");
    }
    if (status == ApprovalStatus.PENDING) {
      if (decidedAt != null || decidedBy != null) {
        throw new IllegalArgumentException("A pending approval cannot carry a decision");
      }
    } else if (decidedAt == null || decidedBy == null || decidedBy.isBlank()) {
      throw new IllegalArgumentException("A decided approval requires decidedAt and decidedBy");
    }
  }
}
