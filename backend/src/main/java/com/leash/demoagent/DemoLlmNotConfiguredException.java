package com.leash.demoagent;

/** The requested demo mode is not available in this build. */
public class DemoLlmNotConfiguredException extends RuntimeException {

  public DemoLlmNotConfiguredException() {
    super("LLM mode is not configured. Use SCRIPTED mode or configure a model in Slice 2.9.");
  }
}
