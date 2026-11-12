package com.leash.payment.app;

import com.leash.payment.domain.PaymentRecord;
import java.util.Objects;

/**
 * One payment row with the owning agent's display name.
 *
 * <p>The name is joined in the page query so the list endpoint never issues one agent lookup per
 * payment.
 */
public record AdminPaymentRow(String agentName, PaymentRecord payment) {

  public AdminPaymentRow {
    Objects.requireNonNull(agentName, "agentName is required");
    Objects.requireNonNull(payment, "payment is required");
  }
}
