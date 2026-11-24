package com.leash.payment.app;

/** What one reconciler pass did. Returned for tests and structured logging. */
public record ReconciliationSummary(
    int approvedRetried, int statusPolled, int terminalTransitions, int failures) {}
