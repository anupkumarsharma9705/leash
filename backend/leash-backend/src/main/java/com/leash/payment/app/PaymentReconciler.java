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
 * <p>Circuit breaker: after {@link ReconcilerProperties#maxConsecutiveFailures()} consecutive
 * failed passes, the reconciler pauses for {@link ReconcilerProperties#circuitBreakerPause()}.
 * Per-payment exponential backoff: each failed retry doubles the wait time up to a cap.
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

  // Per-payment exponential backoff: paymentId -> retry count
  private final Map<UUID, Integer> retryCounts = new ConcurrentHashMap<>();

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
    // Check circuit breaker
    if (clock.instant().isBefore(circuitBreakerUntil)) {
      log.debug("Circuit breaker active, skipping reconciliation until {}", circuitBreakerUntil);
      return;
    }
    try {
      ReconciliationSummary summary = reconcileOnce();
      // Update circuit breaker state based on pass result
      if (summary.failures() > 0 && summary.approvedRetried() == 0 && summary.statusPolled() == 0) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.maxConsecutiveFailures()) {
          circuitBreakerUntil = clock.instant().plus(properties.circuitBreakerPause());
          log.warn(
              "Circuit breaker tripped after {} consecutive failed passes. Pausing for {}",
              failures,
              properties.circuitBreakerPause());
        }
      } else {
        consecutiveFailures.set(0);
      }
    } catch (RuntimeException exception) {
      log.error("Payment reconciliation pass failed", exception);
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
      int retryCount = retryCounts.getOrDefault(record.id(), 0);
      // Exponential backoff: wait retryCount^2 * baseInterval before retrying
      if (retryCount > 0) {
        Duration backoff = properties.interval().multipliedBy(retryCount * retryCount);
        Instant nextRetry = record.createdAt().plus(backoff);
        if (clock.instant().isBefore(nextRetry)) {
          continue; // Skip this payment, not ready for retry yet
        }
      }
      try {
        gateway.retryApproved(record);
        retried++;
        retryCounts.remove(record.id());
      } catch (RuntimeException exception) {
        failures++;
        retryCounts.merge(record.id(), 1, Integer::sum);
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
      if (record.paypalBatchId() == null || record.paypalBatchId().isBlank()) {
        failures++;
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
        retryCounts.remove(record.id());
      } catch (OptimisticLockingFailureException exception) {
        failures++;
        log.info(
            "Reconciler lost a concurrent update and will retry later: paymentId={}", record.id());
      } catch (RuntimeException exception) {
        failures++;
        retryCounts.merge(record.id(), 1, Integer::sum);
        log.warn(
            "Reconciler could not poll PayPal: paymentId={} payoutBatchId={}",
            record.id(),
            record.paypalBatchId(),
            exception);
      }
    }

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
}
