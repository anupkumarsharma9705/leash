package com.leash.policy;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The spending policy attached to one agent.
 *
 * <p>Payee emails are normalized at construction, so every comparison in the policy engine is
 * case-insensitive and ignores surrounding whitespace.
 */
public record AgentPolicy(
    long weeklyLimitMinor, long autoApproveThresholdMinor, Set<String> allowedPayees) {

  public AgentPolicy {
    if (weeklyLimitMinor < 0) {
      throw new IllegalArgumentException("weeklyLimitMinor must not be negative");
    }
    if (autoApproveThresholdMinor < 0) {
      throw new IllegalArgumentException("autoApproveThresholdMinor must not be negative");
    }
    Objects.requireNonNull(allowedPayees, "allowedPayees is required");
    allowedPayees =
        allowedPayees.stream()
            .map(AgentPolicy::normalizePayeeEntry)
            .collect(Collectors.toUnmodifiableSet());
  }

  /** Returns {@code true} only when the request payee matches a normalized allow-list entry. */
  public boolean allowsPayee(String payeeEmail) {
    String normalized = normalizePayee(payeeEmail);
    return !normalized.isEmpty() && allowedPayees.contains(normalized);
  }

  private static String normalizePayeeEntry(String payeeEmail) {
    if (payeeEmail == null) {
      throw new IllegalArgumentException("allowed payee must not be null");
    }
    String normalized = payeeEmail.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("allowed payee must not be blank");
    }
    return normalized;
  }

  private static String normalizePayee(String payeeEmail) {
    return payeeEmail == null ? "" : payeeEmail.trim().toLowerCase(Locale.ROOT);
  }
}
