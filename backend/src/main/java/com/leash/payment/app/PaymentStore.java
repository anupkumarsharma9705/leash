package com.leash.payment.app;

import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.ApprovalStatus;
import com.leash.payment.domain.BudgetTotals;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PendingApproval;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for payment requests and their approvals. */
public interface PaymentStore {

  void insert(PaymentRecord record);

  Optional<PaymentRecord> findByAgentAndIdempotencyKey(UUID agentId, String idempotencyKey);

  Optional<PaymentRecord> findById(UUID paymentId);

  /**
   * Returns one page of payments matching the filter, newest first, joined with the owning agent's
   * name so list rendering never needs a per-row lookup.
   *
   * @param offset number of rows to skip, from the start of the filtered, sorted result
   * @param limit page size, already validated by the caller
   */
  List<AdminPaymentRow> findPage(PaymentListFilter filter, long offset, int limit);

  /** Counts every payment matching the filter. Uses the same predicates as {@link #findPage}. */
  long count(PaymentListFilter filter);

  /**
   * Returns payments in one state whose {@code updated_at} is older than the given instant, oldest
   * first. Used by the reconciler to find stuck submissions and stale status polls.
   */
  List<PaymentRecord> findStaleByState(PaymentState state, Instant updatedBefore, int limit);

  /** Budget aggregates for one agent and one half-open period, scoped to committed states. */
  BudgetTotals budgetTotals(UUID agentId, Instant startInclusive, Instant endExclusive);

  /** Budget aggregates for every agent in one half-open period, grouped by agent id. */
  Map<UUID, BudgetTotals> budgetTotalsByAgent(Instant startInclusive, Instant endExclusive);

  void insertApproval(UUID approvalId, UUID paymentId, Instant requestedAt);

  /** Returns every pending approval with its payment, oldest request first. One joined query. */
  List<PendingApproval> findPendingApprovals();

  Optional<ApprovalRecord> findApproval(UUID approvalId);

  /**
   * Moves a pending approval to a terminal decision using optimistic versioning.
   *
   * <p>The {@code status = 'PENDING'} guard makes the update a compare-and-set: exactly one racing
   * caller can win. A same-decision repeat is handled by the caller before it gets here.
   *
   * @return the decided record when this call won, or empty when another decision already committed
   */
  Optional<ApprovalRecord> decideApproval(
      UUID approvalId,
      long expectedVersion,
      ApprovalStatus status,
      String decidedBy,
      String note,
      Instant decidedAt);

  /**
   * Applies a state transition using optimistic versioning.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException when the version no longer
   *     matches
   */
  PaymentRecord updateState(
      UUID paymentId, long expectedVersion, PaymentState state, Instant updatedAt);

  /**
   * Applies a PayPal outcome using optimistic versioning.
   *
   * @throws org.springframework.dao.OptimisticLockingFailureException when the version no longer
   *     matches
   */
  PaymentRecord updatePayPalState(
      UUID paymentId,
      long expectedVersion,
      PaymentState state,
      String paypalBatchId,
      String rawStatus,
      Instant updatedAt);
}
