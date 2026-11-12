package com.leash.paypal;

/** A point-in-time PayPal payout status. Raw values are stored for audit and troubleshooting. */
public record PayPalPayoutStatus(
    String payoutBatchId, String rawBatchStatus, String rawItemStatus) {}
