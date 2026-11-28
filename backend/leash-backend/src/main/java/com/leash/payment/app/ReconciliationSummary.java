package com.leash.payment.app;

/**
 * What one reconciler pass did. Returned for tests and structured logging.
 *
 * <p>{@code approvedRetried} counts only retries that left {@code APPROVED} (whether they reached
 * {@code SUBMITTED} or were definitively failed). A retry that stays {@code APPROVED} is counted in
 * {@code failures} so the circuit breaker can see it.
 */
public record ReconciliationSummary(
    int approvedRetried, int statusPolled, int terminalTransitions, int failures) {}
