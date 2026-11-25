package com.leash.demoagent;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the optional LLM demo agent.
 *
 * <p>The endpoint must speak the OpenAI-compatible chat-completions tool-calling protocol. Leaving
 * any of {@code baseUrl}, {@code apiKey}, or {@code model} blank keeps LLM mode disabled; the
 * deterministic SCRIPTED mode remains available.
 */
@Validated
@ConfigurationProperties("leash.llm")
public record LlmProperties(
    @DefaultValue("") String baseUrl,
    @DefaultValue("") String apiKey,
    @DefaultValue("") String model,
    @DefaultValue("30s") @NotNull Duration timeout,
    @DefaultValue("8") @Min(1) @Max(16) int maxIterations,
    @DefaultValue("0.0") @DecimalMin("0.0") @DecimalMax("2.0") double temperature) {

  public LlmProperties {
    baseUrl = trim(baseUrl);
    apiKey = trim(apiKey);
    model = trim(model);
  }

  public boolean isConfigured() {
    return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
  }

  /** Builds the chat-completions URL, tolerating a trailing slash in the configured base URL. */
  public String chatCompletionsUrl() {
    String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return trimmed + "/chat/completions";
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
