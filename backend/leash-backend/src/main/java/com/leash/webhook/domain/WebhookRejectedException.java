package com.leash.webhook.domain;

/** A verified webhook cannot be accepted when its event envelope is invalid. */
public class WebhookRejectedException extends RuntimeException {

  private final String code;

  public WebhookRejectedException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
