package com.leash.webhook.api;

import com.leash.paypal.PayPalWebhookHeaders;
import com.leash.webhook.app.WebhookReceiver;
import com.leash.webhook.domain.WebhookRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPal webhook endpoint.
 *
 * <p>The body is read as bytes directly from the request so the exact signed payload reaches the
 * verifier. No Spring message converter is allowed to parse it first.
 */
@RestController
public class PayPalWebhookController {

  static final int MAX_BODY_BYTES = 1024 * 1024;

  private final WebhookReceiver receiver;

  public PayPalWebhookController(WebhookReceiver receiver) {
    this.receiver = receiver;
  }

  @PostMapping(path = "/webhooks/paypal", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> receive(
      @RequestHeader(name = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
      @RequestHeader(name = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime,
      @RequestHeader(name = "PAYPAL-TRANSMISSION-SIG", required = false)
          String transmissionSignature,
      @RequestHeader(name = "PAYPAL-CERT-URL", required = false) String certUrl,
      @RequestHeader(name = "PAYPAL-AUTH-ALGO", required = false) String authAlgorithm,
      HttpServletRequest request)
      throws IOException {

    byte[] rawBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (rawBody.length > MAX_BODY_BYTES) {
      throw new WebhookRejectedException(
          "WEBHOOK_BODY_TOO_LARGE", "Webhook body exceeds the 1 MiB limit");
    }
    if (rawBody.length == 0) {
      throw new WebhookRejectedException("INVALID_WEBHOOK_EVENT", "Webhook body is empty");
    }

    PayPalWebhookHeaders headers =
        new PayPalWebhookHeaders(
            transmissionId, transmissionTime, transmissionSignature, certUrl, authAlgorithm);
    receiver.receive(headers, rawBody);
    return ResponseEntity.ok().build();
  }
}
