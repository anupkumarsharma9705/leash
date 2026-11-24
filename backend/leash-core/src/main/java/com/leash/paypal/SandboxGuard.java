package com.leash.paypal;

import java.net.URI;

/**
 * Refuses to run against a non-sandbox PayPal host unless live mode was deliberately enabled.
 *
 * <p>Kept as a pure function so the rule is unit-tested without a Spring context.
 */
public final class SandboxGuard {

  private SandboxGuard() {}

  public static void verify(String baseUrl, boolean allowLive) {
    if (allowLive) {
      return;
    }
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("PayPal base URL is not a valid URL", exception);
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (!"https".equalsIgnoreCase(scheme)
        || host == null
        || !host.endsWith(".sandbox.paypal.com")) {
      throw new IllegalStateException(
          "Refusing to start: PayPal base URL is not a sandbox host. "
              + "Set LEASH_ALLOW_LIVE=true only for a deliberate live run.");
    }
  }
}
