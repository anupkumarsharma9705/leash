package com.leash.demoagent;

/** A safe, non-secret failure from the configured model endpoint. */
public class AgentModelException extends RuntimeException {

  public AgentModelException(String message) {
    super(message);
  }

  public AgentModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
