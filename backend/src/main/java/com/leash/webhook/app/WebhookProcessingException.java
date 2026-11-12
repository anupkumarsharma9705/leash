package com.leash.webhook.app;

/** The event was persisted, but applying it to a payment failed. */
public class WebhookProcessingException extends RuntimeException {

  public WebhookProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
