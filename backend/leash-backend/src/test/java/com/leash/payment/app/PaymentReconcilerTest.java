package com.leash.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.leash.common.MutableClock;
import com.leash.payment.ReconcilerProperties;
import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.ApprovalStatus;
import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.BudgetTotals;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PendingApproval;
import com.leash.policy.PolicyOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the reconciler's failure policy.
 *
 * <p>The integration test proves the database-facing behavior; these tests isolate the in-memory
 * circuit breaker and backoff-pruning state so they can be exercised without a PayPal or database
 * dependency.
 */
class PaymentReconcilerTest {

  private static final Instant START = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void tripsTheBreakerAfterConsecutiveFailedPassesAndSkipsUntilThePauseExpires() {
    PaymentRecord payment = approvedPayment();
    FakePaymentStore store = new FakePaymentStore(payment);
    TransientGateway gateway = new TransientGateway();
    MutableClock clock = new MutableClock(START);
    PaymentReconciler reconciler =
        new PaymentReconciler(
            store, gateway, null, null, properties(2, Duration.ofMinutes(5)), clock);

    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(1);

    clock.advance(Duration.ofSeconds(31));
    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(2);

    // The second failed pass tripped the breaker; this tick must not call PayPal.
    clock.advance(Duration.ofSeconds(1));
    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(2);

    clock.advance(Duration.ofMinutes(5));
    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(3);
  }

  @Test
  void prunesBackoffForPaymentsThatAreNoLongerOpen() {
    PaymentRecord payment = approvedPayment();
    FakePaymentStore store = new FakePaymentStore(payment);
    TransientGateway gateway = new TransientGateway();
    MutableClock clock = new MutableClock(START);
    PaymentReconciler reconciler =
        new PaymentReconciler(
            store, gateway, null, null, properties(10, Duration.ofMinutes(5)), clock);

    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(1);

    // The payment was resolved elsewhere (for example by a webhook) while its backoff was active.
    // The next pass must drop the stale entry.
    clock.advance(Duration.ofSeconds(1));
    store.prunedState = PaymentState.SUCCEEDED;
    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(1);

    // If the row is APPROVED again (a test-only inconsistency), the old backoff must be gone.
    store.prunedState = PaymentState.APPROVED;
    reconciler.scheduledReconcile();
    assertThat(gateway.attempts).isEqualTo(2);
  }

  private static ReconcilerProperties properties(int maxFailures, Duration pause) {
    return new ReconcilerProperties(
        true,
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofMinutes(10),
        10,
        maxFailures,
        pause,
        Duration.ofHours(1));
  }

  private static PaymentRecord approvedPayment() {
    Instant created = START.minusSeconds(120);
    return new PaymentRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "invoice-1",
        "fingerprint",
        "alice@example.com",
        1_200,
        "USD",
        "Invoice 1",
        PaymentState.APPROVED,
        PolicyOutcome.APPROVE,
        List.of(),
        null,
        null,
        new BudgetSnapshot(50_000, 1_200, 0),
        0,
        created,
        created);
  }

  /** Returns the same APPROVED row and reports a transient failure without changing its state. */
  private static final class TransientGateway extends PaymentGatewayService {

    private int attempts;

    private TransientGateway() {
      super(null, null);
    }

    @Override
    public PaymentResult retryApproved(PaymentRecord record) {
      attempts++;
      return PaymentResult.from(record, false);
    }
  }

  private static final class FakePaymentStore implements PaymentStore {

    private final PaymentRecord payment;
    private PaymentState prunedState = PaymentState.APPROVED;

    private FakePaymentStore(PaymentRecord payment) {
      this.payment = payment;
    }

    @Override
    public List<PaymentRecord> findStaleByState(
        PaymentState state, Instant updatedBefore, int limit) {
      return state == PaymentState.APPROVED ? List.of(payment) : List.of();
    }

    @Override
    public Map<UUID, PaymentState> statesFor(Collection<UUID> paymentIds) {
      return Map.of(payment.id(), prunedState);
    }

    @Override
    public void insert(PaymentRecord record) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PaymentRecord> findByAgentAndIdempotencyKey(
        UUID agentId, String idempotencyKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PaymentRecord> findById(UUID paymentId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AdminPaymentRow> findPage(PaymentListFilter filter, long offset, int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long count(PaymentListFilter filter) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BudgetTotals budgetTotals(UUID agentId, Instant startInclusive, Instant endExclusive) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<UUID, BudgetTotals> budgetTotalsByAgent(
        Instant startInclusive, Instant endExclusive) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertApproval(UUID approvalId, UUID paymentId, Instant requestedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PendingApproval> findPendingApprovals() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ApprovalRecord> findApproval(UUID approvalId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ApprovalRecord> decideApproval(
        UUID approvalId,
        long expectedVersion,
        ApprovalStatus status,
        String decidedBy,
        String note,
        Instant decidedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PaymentRecord updateState(
        UUID paymentId, long expectedVersion, PaymentState state, Instant updatedAt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PaymentRecord updatePayPalState(
        UUID paymentId,
        long expectedVersion,
        PaymentState state,
        String paypalBatchId,
        String rawStatus,
        Instant updatedAt) {
      throw new UnsupportedOperationException();
    }
  }
}
