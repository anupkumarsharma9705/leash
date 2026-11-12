package com.leash.payment.app;

import com.leash.payment.domain.InvalidPaymentQueryException;
import com.leash.payment.domain.PaymentState;
import java.time.Instant;
import java.util.UUID;

/**
 * Filters for the administrative payment list.
 *
 * <p>{@code from} is inclusive and {@code to} is exclusive, so adjacent windows never overlap. Both
 * are instants (ISO-8601 UTC at the boundary), not dates: the console can page through a precise
 * time range without time-zone ambiguity.
 */
public record PaymentListFilter(PaymentState status, UUID agentId, Instant from, Instant to) {

  public PaymentListFilter {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new InvalidPaymentQueryException("from must be before to");
    }
  }

  public static PaymentListFilter none() {
    return new PaymentListFilter(null, null, null, null);
  }
}
