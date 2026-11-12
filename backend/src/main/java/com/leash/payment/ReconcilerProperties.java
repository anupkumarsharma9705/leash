package com.leash.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Scheduling thresholds for the payment reconciler.
 *
 * <p>An {@code APPROVED} payment older than {@link #approvedRetryAfter()} is resubmitted (safe
 * because PayPal receives the payment id as its idempotency key). A {@code SUBMITTED} payment older
 * than {@link #submittedPollAfter()} is polled for its current payout status.
 *
 * <p>Circuit breaker: after {@link #maxConsecutiveFailures()} consecutive failed passes, the
 * reconciler pauses for {@link #circuitBreakerPause()} to avoid hammering PayPal.
 */
@Validated
@ConfigurationProperties(prefix = "leash.reconciler")
public record ReconcilerProperties(
    @DefaultValue("true") boolean enabled,
    @NotNull @DefaultValue("30s") Duration interval,
    @NotNull @DefaultValue("60s") Duration approvedRetryAfter,
    @NotNull @DefaultValue("10m") Duration submittedPollAfter,
    @DefaultValue("100") @Min(1) int batchSize,
    @DefaultValue("10") @Min(1) int maxConsecutiveFailures,
    @DefaultValue("5m") Duration circuitBreakerPause) {}
