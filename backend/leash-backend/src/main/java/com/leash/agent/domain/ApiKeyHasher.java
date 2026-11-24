package com.leash.agent.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SHA-256 hashing and constant-time comparison for agent API keys.
 *
 * <p>Only the hash is persisted. Comparison uses {@link MessageDigest#isEqual(byte[], byte[])},
 * never {@link String#equals(Object)}, so an attacker cannot learn the hash from comparison timing.
 */
public final class ApiKeyHasher {

  private static final String ALGORITHM = "SHA-256";
  private static final HexFormat HEX = HexFormat.of();

  public String hash(String plaintextKey) {
    Objects.requireNonNull(plaintextKey, "plaintextKey is required");
    return HEX.formatHex(digest(plaintextKey));
  }

  /** Returns {@code true} only when the presented key hashes to the stored hash. */
  public boolean matches(String presentedKey, String storedHash) {
    if (presentedKey == null || storedHash == null) {
      return false;
    }
    byte[] expected;
    try {
      expected = HEX.parseHex(storedHash);
    } catch (IllegalArgumentException exception) {
      return false;
    }
    return MessageDigest.isEqual(digest(presentedKey), expected);
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance(ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
