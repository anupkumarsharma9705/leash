package com.leash.paypal;

/** The identifiers and raw batch status returned when PayPal accepts a payout. */
public record PayoutSubmission(String payoutBatchId, String rawBatchStatus) {}
