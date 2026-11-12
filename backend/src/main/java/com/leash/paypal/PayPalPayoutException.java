package com.leash.paypal;

/** Raised when a PayPal Payouts call cannot be completed. */
public class PayPalPayoutException extends RuntimeException {

  private final Integer httpStatus;
  private final String debugId;

  public PayPalPayoutException(String message) {
    this(message, null, null, null);
  }

  public PayPalPayoutException(String message, Integer httpStatus, String debugId) {
    this(message, httpStatus, debugId, null);
  }

  public PayPalPayoutException(
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
