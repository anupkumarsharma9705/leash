package com.leash.payment.domain;

/**
 * Raised when a payment does not exist or is not owned by the authenticated agent.
 *
 * <p>Both cases produce the same {@code 404} so an agent cannot probe another agent's payment ids.
 */
public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException() {
    super("Payment not found");
  }
}
