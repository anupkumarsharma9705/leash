package com.leash.payment.domain;

import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One persisted payment request.
 *
 * <p>The budget snapshot is the response returned at decision time, stored so an idempotent replay
 * is byte-for-byte consistent even after later spending.
 */
public record PaymentRecord(
    UUID id,
    UUID agentId,
    String idempotencyKey,
    String requestFingerprint,
    String payeeEmail,
    long amountMinor,
    String currency,
    String memo,
    PaymentState state,
    PolicyOutcome decisionOutcome,
    List<PolicyReason> decisionReasons,
    String paypalBatchId,
    String rawStatus,
    BudgetSnapshot budget,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public PaymentRecord {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(agentId, "agentId is required");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey is required");
    }
    if (requestFingerprint == null || requestFingerprint.isBlank()) {
      throw new IllegalArgumentException("requestFingerprint is required");
    }
    if (payeeEmail == null || payeeEmail.isBlank()) {
      throw new IllegalArgumentException("payeeEmail is required");
    }
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    if (currency == null || currency.length() != 3) {
      throw new IllegalArgumentException("currency must be a three-letter code");
    }
    Objects.requireNonNull(memo, "memo is required");
    Objects.requireNonNull(state, "state is required");
    Objects.requireNonNull(decisionOutcome, "decisionOutcome is required");
    Objects.requireNonNull(budget, "budget is required");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    Objects.requireNonNull(createdAt, "createdAt is required");
    Objects.requireNonNull(updatedAt, "updatedAt is required");
    decisionReasons = List.copyOf(decisionReasons);
    if (decisionOutcome == PolicyOutcome.DENY && decisionReasons.isEmpty()) {
      throw new IllegalArgumentException("DENY requires at least one reason");
    }
    if (decisionOutcome != PolicyOutcome.DENY && !decisionReasons.isEmpty()) {
      throw new IllegalArgumentException("Non-DENY payments cannot carry reasons");
    }
    if (decisionOutcome == PolicyOutcome.DENY && state != PaymentState.DENIED) {
      throw new IllegalArgumentException("DENY must be persisted as DENIED");
    }
  }
}
