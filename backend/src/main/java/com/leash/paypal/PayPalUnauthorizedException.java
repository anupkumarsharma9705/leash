package com.leash.paypal;

/**
 * Raised when a PayPal resource call is rejected with HTTP 401.
 *
 * <p>The OAuth client catches exactly this exception to refresh the cached token and retry once.
 */
public class PayPalUnauthorizedException extends RuntimeException {

  public PayPalUnauthorizedException(String message) {
    super(message);
  }
}
