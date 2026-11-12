package com.leash.payment.domain;

/** Raised when an administrative payment query carries invalid paging or filter values. */
public class InvalidPaymentQueryException extends RuntimeException {

  public InvalidPaymentQueryException(String message) {
    super(message);
  }
}
