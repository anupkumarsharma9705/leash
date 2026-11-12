package com.leash.paypal;

/** The PayPal transmission headers that authenticate an incoming webhook. */
public record PayPalWebhookHeaders(
    String transmissionId,
    String transmissionTime,
    String transmissionSignature,
    String certUrl,
    String authAlgorithm) {

  public boolean isComplete() {
    return hasText(transmissionId)
        && hasText(transmissionTime)
        && hasText(transmissionSignature)
        && hasText(certUrl)
        && hasText(authAlgorithm);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
