package com.leash.agent.domain;

/** Raised when an API key is missing, malformed, unknown, or revoked. */
public class InvalidApiKeyException extends RuntimeException {

  public InvalidApiKeyException() {
    super("Invalid API key");
  }
}
