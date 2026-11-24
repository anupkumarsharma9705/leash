package com.leash.paypal;

import com.leash.payment.domain.PaymentState;
import java.util.Locale;

/**
 * Translates PayPal Payouts status strings into Leash payment states.
 *
 * <p>An item status is the source of truth for whether money reached the recipient. A batch status
 * of {@code SUCCESS} only means the batch finished processing; its items may still be {@code
 * ONHOLD} or {@code UNCLAIMED}. Unknown statuses stay {@code SUBMITTED} so a future PayPal status
 * can never be mistaken for a completed payment.
 */
public final class PayPalStatusMapper {

  private PayPalStatusMapper() {}

  /** Resolves the payment state, preferring the payout item status when it is present. */
  public static PaymentState resolve(String rawBatchStatus, String rawItemStatus) {
    if (rawItemStatus != null && !rawItemStatus.isBlank()) {
      return fromItemStatus(rawItemStatus);
    }
    return fromBatchStatus(rawBatchStatus);
  }

  /** Maps a Payouts item transaction status. */
  public static PaymentState fromItemStatus(String rawStatus) {
    if (rawStatus == null) {
      return PaymentState.SUBMITTED;
    }
    return switch (normalize(rawStatus)) {
      case "SUCCESS" -> PaymentState.SUCCEEDED;
      case "FAILED", "BLOCKED", "RETURNED", "REFUNDED", "REVERSED", "CANCELED" ->
          PaymentState.FAILED;
      case "PENDING", "UNCLAIMED", "ONHOLD", "HELD" -> PaymentState.SUBMITTED;
      default -> PaymentState.SUBMITTED;
    };
  }

  /** Maps a Payouts batch status. Batch {@code SUCCESS} is not proof of item success. */
  public static PaymentState fromBatchStatus(String rawStatus) {
    if (rawStatus == null) {
      return PaymentState.SUBMITTED;
    }
    return switch (normalize(rawStatus)) {
      case "DENIED", "CANCELED" -> PaymentState.FAILED;
      case "PENDING", "PROCESSING", "SUCCESS" -> PaymentState.SUBMITTED;
      default -> PaymentState.SUBMITTED;
    };
  }

  private static String normalize(String rawStatus) {
    return rawStatus.trim().toUpperCase(Locale.ROOT);
  }
}
