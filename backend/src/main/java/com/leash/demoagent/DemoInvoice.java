package com.leash.demoagent;

/**
 * One invoice in the deterministic demo scene set.
 *
 * <p>Amounts are integer minor units. Invoice text is untrusted data from the model's point of
 * view, even though the bundled file is authored by us.
 */
public record DemoInvoice(
    String id, String label, String payeeEmail, long amountMinor, String currency, String memo) {

  public DemoInvoice {
    requireText(id, "id", 64);
    requireText(label, "label", 120);
    requireText(payeeEmail, "payeeEmail", 254);
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive");
    }
    requireText(currency, "currency", 3);
    if (!"USD".equals(currency)) {
      throw new IllegalArgumentException("only USD is supported");
    }
    requireText(memo, "memo", 200);
  }

  /** Idempotency key used for this invoice, exactly once per invoice id. */
  public String idempotencyKey() {
    return "invoice-" + id;
  }

  private static void requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
    }
  }
}
