package com.leash.demoagent;

/** The requested demo mode is not available until an OpenAI-compatible model is configured. */
public class DemoLlmNotConfiguredException extends RuntimeException {

  public DemoLlmNotConfiguredException() {
    super(
        "LLM mode is not configured. Set LEASH_LLM_BASE_URL, LEASH_LLM_API_KEY, and "
            + "LEASH_LLM_MODEL, or use SCRIPTED mode.");
  }
}
