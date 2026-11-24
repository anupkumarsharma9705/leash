package com.leash.policy;

import java.util.Objects;

/** One hard-rule violation, with a stable code and a human-readable message. */
public record PolicyReason(PolicyReasonCode code, String message) {

  public PolicyReason {
    Objects.requireNonNull(code, "code is required");
    if (message == null || message.isBlank()) {
      message = code.message();
    }
  }

  public static PolicyReason of(PolicyReasonCode code) {
    return new PolicyReason(code, code.message());
  }
}
