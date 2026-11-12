package com.leash.policy;

/** Stable reason codes returned to agents and written to the ledger. */
public enum PolicyReasonCode {
  AGENT_SUSPENDED("Agent is suspended"),
  CURRENCY_NOT_SUPPORTED("Currency is not supported"),
  INVALID_AMOUNT("Amount must be positive"),
  AMOUNT_EXCEEDS_CAP("Amount exceeds the maximum allowed payment"),
  PAYEE_NOT_ALLOWED("Payee is not on the allow-list"),
  BUDGET_EXCEEDED("Weekly budget would be exceeded");

  private final String message;

  PolicyReasonCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
