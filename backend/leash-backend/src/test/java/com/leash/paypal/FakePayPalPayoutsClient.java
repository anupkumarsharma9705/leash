package com.leash.paypal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory test double for payment and approval tests.
 *
 * <p>Submits succeed immediately with a synthetic batch id and {@code PENDING} status. Tests set
 * the eventual batch/item status explicitly.
 */
public final class FakePayPalPayoutsClient implements PayPalPayoutsClient {

  private final AtomicInteger sequence = new AtomicInteger();
  private final Map<String, PayoutRequest> requests = new ConcurrentHashMap<>();
  private final Map<String, PayPalPayoutStatus> statuses = new ConcurrentHashMap<>();
  private final List<PayoutSubmission> submissions = new CopyOnWriteArrayList<>();

  private volatile RuntimeException failure;
  private volatile RuntimeException lookupFailure;
  private volatile long submitDelayMillis;
  private volatile CountDownLatch submitEntered = new CountDownLatch(0);
  private volatile CountDownLatch submitRelease = new CountDownLatch(0);

  @Override
  public synchronized PayoutSubmission submit(PayoutRequest request) {
    if (failure != null) {
      throw failure;
    }

    CountDownLatch release = submitRelease;
    submitEntered.countDown();
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new PayPalPayoutException("Fake submission was not released", null, null);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new PayPalPayoutException("Fake submission interrupted", null, null, exception);
    }

    if (submitDelayMillis > 0) {
      try {
        Thread.sleep(submitDelayMillis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new PayPalPayoutException("Fake submission interrupted", null, null, exception);
      }
    }

    String batchId = "fake-batch-" + sequence.incrementAndGet();
    requests.put(batchId, request);
    PayPalPayoutStatus status = new PayPalPayoutStatus(batchId, "PENDING", null);
    statuses.put(batchId, status);
    PayoutSubmission submission = new PayoutSubmission(batchId, "PENDING");
    submissions.add(submission);
    return submission;
  }

  @Override
  public PayPalPayoutStatus lookup(String payoutBatchId) {
    RuntimeException lookupProblem = lookupFailure;
    if (lookupProblem != null) {
      lookupFailure = null;
      throw lookupProblem;
    }
    PayPalPayoutStatus status = statuses.get(payoutBatchId);
    if (status == null) {
      throw new PayPalPayoutException("Unknown fake payout batch: " + payoutBatchId);
    }
    return status;
  }

  public synchronized void setStatus(
      String payoutBatchId, String rawBatchStatus, String rawItemStatus) {
    if (!statuses.containsKey(payoutBatchId)) {
      throw new IllegalArgumentException("Unknown fake payout batch: " + payoutBatchId);
    }
    statuses.put(
        payoutBatchId, new PayPalPayoutStatus(payoutBatchId, rawBatchStatus, rawItemStatus));
  }

  public synchronized void failNextSubmission(RuntimeException failure) {
    this.failure = failure;
  }

  /** Makes the next {@link #lookup(String)} throw once, leaving the payment unchanged. */
  public synchronized void failNextLookup(RuntimeException failure) {
    this.lookupFailure = failure;
  }

  public synchronized void clearFailure() {
    this.failure = null;
  }

  public void setSubmitDelayMillis(long submitDelayMillis) {
    this.submitDelayMillis = Math.max(0, submitDelayMillis);
  }

  /**
   * Makes the next submission signal {@link #awaitSubmitEntered(Duration)} and then wait until
   * {@link #releaseSubmissions()} is called. Used to prove the database work committed before
   * PayPal was contacted.
   */
  public void blockSubmissions() {
    submitEntered = new CountDownLatch(1);
    submitRelease = new CountDownLatch(1);
  }

  public boolean awaitSubmitEntered(Duration timeout) throws InterruptedException {
    return submitEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void releaseSubmissions() {
    submitRelease.countDown();
  }

  /** Clears every recorded call and failure between tests that share a Spring context. */
  public synchronized void reset() {
    failure = null;
    lookupFailure = null;
    submitDelayMillis = 0;
    requests.clear();
    statuses.clear();
    submissions.clear();
    sequence.set(0);
    submitEntered = new CountDownLatch(0);
    submitRelease = new CountDownLatch(0);
  }

  public List<PayoutSubmission> submissions() {
    return List.copyOf(submissions);
  }

  public PayoutRequest requestFor(String payoutBatchId) {
    return requests.get(payoutBatchId);
  }
}
