package com.leash.paypal;

import java.time.Duration;

/** Injectable sleep boundary so retry tests run without real backoff delays. */
@FunctionalInterface
interface Sleeper {

  void sleep(Duration duration);

  static Sleeper system() {
    return duration -> {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new PayPalPayoutException("Interrupted while waiting to retry a PayPal call");
      }
    };
  }
}
