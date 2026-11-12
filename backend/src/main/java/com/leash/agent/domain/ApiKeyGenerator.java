package com.leash.agent.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generates agent API keys in the form {@code lsh_<32 random bytes base64url>}.
 *
 * <p>The plaintext key exists only in the creation response. The database stores its SHA-256 hash
 * and the first eight characters as an identification prefix.
 */
public final class ApiKeyGenerator {

  public static final String PREFIX = "lsh_";
  public static final int RANDOM_BYTES = 32;
  public static final int PREFIX_LENGTH = 8;
  public static final int TOTAL_LENGTH = 47;

  private static final Pattern RANDOM_PART = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final SecureRandom secureRandom;

  public ApiKeyGenerator(SecureRandom secureRandom) {
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
  }

  public String generate() {
    byte[] randomBytes = new byte[RANDOM_BYTES];
    secureRandom.nextBytes(randomBytes);
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }

  /** Returns {@code true} only for strings in the exact generated format. */
  public boolean isWellFormed(String key) {
    if (key == null
        || !key.startsWith(PREFIX)
        || key.length() != TOTAL_LENGTH
        || !RANDOM_PART.matcher(key.substring(PREFIX.length())).matches()) {
      return false;
    }
    try {
      return Base64.getUrlDecoder().decode(key.substring(PREFIX.length())).length == RANDOM_BYTES;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  /** The non-secret identification prefix: the first eight characters of a well-formed key. */
  public String prefix(String key) {
    if (!isWellFormed(key)) {
      throw new IllegalArgumentException("API key is not in the expected format");
    }
    return key.substring(0, PREFIX_LENGTH);
  }
}
