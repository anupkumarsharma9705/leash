package com.leash.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Administrative credentials.
 *
 * <p>There is deliberately no default: the application refuses to start unless both values are
 * supplied. Only a BCrypt hash is accepted for the password, so the plaintext password never lives
 * in configuration or logs.
 */
@Validated
@ConfigurationProperties(prefix = "leash.admin")
public record AdminProperties(
    @NotBlank @Size(max = 100) String username, @NotBlank String passwordHash) {

  /** BCrypt hashes are exactly 60 characters and start with a version marker. */
  private static final Pattern BCRYPT_HASH =
      Pattern.compile("\\A\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}\\z");

  public AdminProperties {
    if (username != null) {
      username = username.trim();
    }
    if (passwordHash != null
        && !passwordHash.isBlank()
        && !BCRYPT_HASH.matcher(passwordHash).matches()) {
      throw new IllegalArgumentException(
          "leash.admin.password-hash must be a BCrypt hash (ADMIN_PASSWORD_HASH)");
    }
  }
}
