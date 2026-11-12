package com.leash.paypal;

/**
 * The money-movement port. Payment code depends on this interface, never on the PayPal adapter.
 *
 * <p>One call submits exactly one payout item. Implementations must use an idempotency key so a
 * retry cannot pay twice.
 */
public interface PayPalPayoutsClient {

  PayoutSubmission submit(PayoutRequest request);

  PayPalPayoutStatus lookup(String payoutBatchId);
}
