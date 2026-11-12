package com.leash.demoagent;

/** Raised when a demo route is called before the in-memory demo agent has been provisioned. */
public class DemoAgentNotReadyException extends RuntimeException {

  public DemoAgentNotReadyException(String message) {
    super(message);
  }
}
