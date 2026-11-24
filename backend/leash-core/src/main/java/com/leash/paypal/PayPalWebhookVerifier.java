package com.leash.paypal;

/**
 * Verifies a PayPal webhook through PayPal's own {@code verify-webhook-signature} API.
 *
 * <p>The implementation receives the exact request bytes and must embed them verbatim in the
 * verification request. It returns {@code false} only when PayPal explicitly rejects the signature;
 * transport and configuration failures are exceptions so the caller can distinguish a forged event
 * from an unavailable verifier.
 */
public interface PayPalWebhookVerifier {

  boolean verify(PayPalWebhookHeaders headers, byte[] rawEventJson);
}
