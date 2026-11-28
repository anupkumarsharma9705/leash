package com.leash.payment.app;

import com.leash.payment.ReconcilerProperties;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.paypal.PayPalPayoutStatus;
import com.leash.paypal.PayPalPayoutsClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Repairs payments left behind by a crash or a slow PayPal.
 *
 * <p>Every pass has two independent jobs:
 *
 * <ol>
 *   <li>Resubmit {@code APPROVED} rows older than the retry threshold. PayPal receives the same
 *       payment id as its idempotency key, so a retry cannot create a second payout.
 *   <li>Poll PayPal for {@code SUBMITTED} rows older than the poll threshold. Only a {@code
 *       SUBMITTED} row can move, so a late or contradictory status can never regress a terminal
 *       payment.
 * </ol>
 *
 * <p>Network calls happen outside transactions, mirroring the gateway's committed-before-PayPal
 * rule. Each row is isolated: one PayPal failure does not abort the rest of the pass.
 *
 * <p>The gateway does not throw for expected PayPal failures: it returns the payment in {@code
 * APPROVED} when a submission was transiently unavailable. A pass therefore counts a retry as
 * successful only when the returned payment actually left {@code APPROVED}; a payment that stays
 * {@code APPROVED} is a failed attempt and is retried with per-payment exponential backoff.
 *
 * <p>Circuit breaker: after {@link ReconcilerProperties#maxConsecutiveFailures()} consecutive
 * failed passes, the reconciler pauses for {@link ReconcilerProperties#circuitBreakerPause()}. A
 * pass with no work (for example, every row is inside its backoff window) does not reset the
 * counter, so a healthy-looking idle pass cannot mask a run of failures.
 */
@Service
public class PaymentReconciler {

  private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

  private final PaymentStore paymentStore;
  private final PaymentGatewayService gateway;
  private final PaymentTransactionService transactions;
  private final PayPalPayoutsClient payoutsClient;
  private final ReconcilerProperties properties;
  private final Clock clock;

  // Circuit breaker state
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private volatile Instant circuitBreakerUntil = Instant.EPOCH;

  // Per-payment backoff: paymentId -> failed attempts and the earliest next attempt.
  private final Map<UUID, RetryState> retries = new ConcurrentHashMap<>();

  private record RetryState(int failures, Instant nextAttemptAt) {}

  public PaymentReconciler(
      PaymentStore paymentStore,
      PaymentGatewayService gateway,
      PaymentTransactionService transactions,
      PayPalPayoutsClient payoutsClient,
      ReconcilerProperties properties,
      Clock clock) {
    this.paymentStore = paymentStore;
    this.gateway = gateway;
    this.transactions = transactions;
    this.payoutsClient = payoutsClient;
    this.properties = properties;
    this.clock = clock;
  }

  /** Scheduled entry point. Disabled in tests and in environments without PayPal credentials. */
  @Scheduled(fixedDelayString = "${leash.reconciler.interval:30s}")
  public void scheduledReconcile() {
    if (!properties.enabled()) {
      return;
    }
    if (clock.instant().isBefore(circuitBreakerUntil)) {
      log.debug("Circuit breaker active, skipping reconciliation until {}", circuitBreakerUntil);
      return;
    }
    try {
      ReconciliationSummary summary = reconcileOnce();
      boolean anySuccess = summary.approvedRetried() > 0 || summary.statusPolled() > 0;
      if (summary.failures() > 0 && !anySuccess) {
        registerFailure();
      } else if (anySuccess) {
        consecutiveFailures.set(0);
      }
      // No work at all: leave the breaker counter untouched. Backoff-skips must not look like a
      // clean pass.
    } catch (RuntimeException exception) {
      log.error("Payment reconciliation pass failed", exception);
      registerFailure();
    }
  }

  private void registerFailure() {
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= properties.maxConsecutiveFailures()) {
      circuitBreakerUntil = clock.instant().plus(properties.circuitBreakerPause());
      log.warn(
          "Circuit breaker tripped after {} consecutive failed passes. Pausing for {}",
          failures,
          properties.circuitBreakerPause());
    }
  }

  /** Runs one pass. Visible so integration tests can advance a mutable clock and invoke it. */
  public ReconciliationSummary reconcileOnce() {
    Instant now = clock.instant();
    int retried = 0;
    int polled = 0;
    int transitions = 0;
    int failures = 0;

    List<PaymentRecord> approved =
        paymentStore.findStaleByState(
            PaymentState.APPROVED,
            now.minus(properties.approvedRetryAfter()),
            properties.batchSize());
    for (PaymentRecord record : approved) {
      if (isInBackoff(record.id(), now)) {
        continue;
      }
      try {
        PaymentResult result = gateway.retryApproved(record);
        if (result.state() == PaymentState.APPROVED) {
          // The gateway swallowed a transient PayPal/credentials failure. The payment is still
          // APPROVED, so this attempt did not repair it.
          failures++;
          recordFailure(record.id(), now);
          log.warn("Reconciler retry did not leave APPROVED; deferring: paymentId={}", record.id());
        } else {
          retried++;
          retries.remove(record.id());
        }
      } catch (RuntimeException exception) {
        failures++;
        recordFailure(record.id(), now);
        log.warn(
            "Reconciler could not retry a payment; it stays APPROVED: paymentId={}",
            record.id(),
            exception);
      }
    }

    List<PaymentRecord> submitted =
        paymentStore.findStaleByState(
            PaymentState.SUBMITTED,
            now.minus(properties.submittedPollAfter()),
            properties.batchSize());
    for (PaymentRecord record : submitted) {
      if (isInBackoff(record.id(), now)) {
        continue;
      }
      if (record.paypalBatchId() == null || record.paypalBatchId().isBlank()) {
        failures++;
        recordFailure(record.id(), now);
        log.warn(
            "Reconciler found a SUBMITTED payment without a PayPal batch id: paymentId={}",
            record.id());
        continue;
      }
      try {
        PayPalPayoutStatus status = payoutsClient.lookup(record.paypalBatchId());
        PaymentRecord updated = transactions.applyReconciledStatus(record.id(), status);
        polled++;
        if (updated.state() == PaymentState.SUCCEEDED || updated.state() == PaymentState.FAILED) {
          transitions++;
        }
        retries.remove(record.id());
      } catch (OptimisticLockingFailureException exception) {
        PaymentRecord latest = paymentStore.findById(record.id()).orElse(null);
        if (latest != null && latest.state() != PaymentState.SUBMITTED) {
          // A webhook or another poll won the race and already moved the payment. This pass did
          // not fail; the authoritative state is terminal.
          retries.remove(record.id());
          log.info(
              "Reconciler poll lost a concurrent update; payment is now {}: paymentId={}",
              latest.state(),
              record.id());
        } else {
          failures++;
          recordFailure(record.id(), now);
          log.info(
              "Reconciler lost a concurrent update and will retry later: paymentId={}",
              record.id());
        }
      } catch (RuntimeException exception) {
        failures++;
        recordFailure(record.id(), now);
        log.warn(
            "Reconciler could not poll PayPal: paymentId={} payoutBatchId={}",
            record.id(),
            record.paypalBatchId(),
            exception);
      }
    }

    pruneRetries();

    ReconciliationSummary summary =
        new ReconciliationSummary(retried, polled, transitions, failures);
    if (retried > 0 || polled > 0 || failures > 0) {
      log.info(
          "Reconciler pass: approvedRetried={} statusPolled={} terminalTransitions={} failures={}",
          retried,
          polled,
          transitions,
          failures);
    }
    return summary;
  }

  private boolean isInBackoff(UUID paymentId, Instant now) {
    RetryState state = retries.get(paymentId);
    return state != null && now.isBefore(state.nextAttemptAt());
  }

  private void recordFailure(UUID paymentId, Instant now) {
    retries.compute(
        paymentId,
        (id, current) -> {
          int failures = current == null ? 1 : current.failures() + 1;
          return new RetryState(failures, now.plus(backoffFor(failures)));
        });
  }

  private Duration backoffFor(int failures) {
    int exponent = Math.min(Math.max(failures - 1, 0), 20);
    Duration delay;
    try {
      delay = properties.interval().multipliedBy(1L << exponent);
    } catch (ArithmeticException exception) {
      return properties.maxRetryBackoff();
    }
    return delay.compareTo(properties.maxRetryBackoff()) > 0 ? properties.maxRetryBackoff() : delay;
  }

  /**
   * Drops backoff state for payments that are no longer open.
   *
   * <p>Without this, a payment resolved by a webhook or an admin would leave its in-memory entry
   * behind forever. The state query is bounded to the tracked ids, so a long-running process does
   * not accumulate stale entries.
   */
  private void pruneRetries() {
    if (retries.isEmpty()) {
      return;
    }
    Map<UUID, PaymentState> states = paymentStore.statesFor(retries.keySet());
    retries
        .keySet()
        .removeIf(paymentId -> {
          PaymentState state = states.get(paymentId);
          return state != PaymentState.APPROVED && state != PaymentState.SUBMITTED;
        });
  }
}
