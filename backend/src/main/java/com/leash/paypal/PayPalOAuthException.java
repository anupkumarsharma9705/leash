package com.leash.paypal;

/** Raised when a PayPal OAuth token cannot be obtained or validated. */
public class PayPalOAuthException extends RuntimeException {

  public PayPalOAuthException(String message) {
    super(message);
  }

  public PayPalOAuthException(String message, Throwable cause) {
    super(message, cause);
  }
}
