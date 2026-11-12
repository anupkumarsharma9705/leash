package com.leash.paypal;

/** Raised when PayPal cannot complete a webhook signature verification call. */
public class PayPalWebhookVerificationException extends RuntimeException {

  private final Integer httpStatus;
  private final String debugId;

  public PayPalWebhookVerificationException(String message) {
    this(message, null, null, null);
  }

  public PayPalWebhookVerificationException(
      String message, Integer httpStatus, String debugId, Throwable cause) {
    super(message, cause);
    this.httpStatus = httpStatus;
    this.debugId = debugId;
  }

  public Integer httpStatus() {
    return httpStatus;
  }

  public String debugId() {
    return debugId;
  }
}
