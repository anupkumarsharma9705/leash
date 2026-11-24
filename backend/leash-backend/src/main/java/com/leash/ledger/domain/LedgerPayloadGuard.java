package com.leash.ledger.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rejects secret-bearing ledger payloads before they reach the database.
 *
 * <p>This is defense in depth, not a substitute for careful callers. It rejects forbidden field
 * names (tokens, passwords, hashes) and values that look like API keys, bearer tokens, or private
 * keys. Error messages identify the offending path but never echo the value.
 */
public final class LedgerPayloadGuard {

  private static final Set<String> FORBIDDEN_KEYS =
      Set.of(
          "token",
          "accesstoken",
          "refreshtoken",
          "secret",
          "clientsecret",
          "password",
          "apikey",
          "authorization",
          "keyhash",
          "credential",
          "privatekey");

  private static final Pattern FULL_API_KEY = Pattern.compile("lsh_[A-Za-z0-9_-]{43}");
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern PRIVATE_KEY_BLOCK =
      Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

  private LedgerPayloadGuard() {}

  /**
   * Validates a payload tree.
   *
   * @throws SecretInLedgerPayloadException when a secret-bearing field or value is found
   * @throws IllegalArgumentException when the payload contains an unsupported value type
   */
  public static void validate(Map<String, Object> payload) {
    Objects.requireNonNull(payload, "payload is required");
    validateValue(payload, "payload");
  }

  private static void validateValue(Object value, String path) {
    if (value == null) {
      return;
    }
    if (value instanceof String text) {
      validateText(text, path);
      return;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return;
    }
    if (value instanceof Map<?, ?> map) {
      validateMap(map, path);
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      int index = 0;
      for (Object item : iterable) {
        validateValue(item, path + "[" + index++ + "]");
      }
      return;
    }
    throw new IllegalArgumentException(
        "Unsupported ledger payload value at " + path + ": " + value.getClass().getName());
  }

  private static void validateMap(Map<?, ?> map, String path) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Ledger payload keys must be strings at " + path);
      }
      if (isForbiddenKey(key)) {
        throw new SecretInLedgerPayloadException(
            "Ledger payload field '" + path + "." + key + "' is not allowed");
      }
      validateValue(entry.getValue(), path + "." + key);
    }
  }

  private static void validateText(String text, String path) {
    if (FULL_API_KEY.matcher(text).find()) {
      throw new SecretInLedgerPayloadException(
          "Ledger payload value at '" + path + "' looks like an agent API key");
    }
    if (BEARER_TOKEN.matcher(text).find()) {
      throw new SecretInLedgerPayloadException(
          "Ledger payload value at '" + path + "' looks like a bearer token");
    }
    if (PRIVATE_KEY_BLOCK.matcher(text).find()) {
      throw new SecretInLedgerPayloadException(
          "Ledger payload value at '" + path + "' looks like a private key");
    }
  }

  private static boolean isForbiddenKey(String key) {
    String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return FORBIDDEN_KEYS.contains(normalized);
  }
}
