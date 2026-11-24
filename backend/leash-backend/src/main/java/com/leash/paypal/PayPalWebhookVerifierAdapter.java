package com.leash.paypal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * PayPal webhook verifier adapter.
 *
 * <p>The verification request is assembled as bytes: fixed JSON fields are serialized, then the
 * original webhook bytes are appended after {@code "webhook_event":} without parsing or
 * re-serializing them. PayPal signs those exact bytes, so any object round-trip would invalidate a
 * genuine signature.
 */
class PayPalWebhookVerifierAdapter implements PayPalWebhookVerifier {

  private static final Logger log = LoggerFactory.getLogger(PayPalWebhookVerifierAdapter.class);
  private static final String VERIFY_PATH = "/v1/notifications/verify-webhook-signature";
  private static final String DEBUG_ID_HEADER = "PayPal-Debug-Id";

  private final RestClient restClient;
  private final PayPalOAuthClient oauthClient;
  private final PayPalProperties properties;
  private final ObjectMapper objectMapper;

  PayPalWebhookVerifierAdapter(
      RestClient restClient,
      PayPalOAuthClient oauthClient,
      PayPalProperties properties,
      ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.oauthClient = oauthClient;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean verify(PayPalWebhookHeaders headers, byte[] rawEventJson) {
    if (headers == null
        || !headers.isComplete()
        || rawEventJson == null
        || rawEventJson.length == 0) {
      log.warn("Rejecting PayPal webhook with incomplete transmission headers or empty body");
      return false;
    }

    String webhookId = properties.webhookId();
    if (webhookId == null || webhookId.isBlank()) {
      throw new PayPalWebhookVerificationException(
          "PAYPAL_WEBHOOK_ID is not configured; cannot verify PayPal webhooks");
    }

    byte[] verificationRequest = buildVerificationRequest(headers, webhookId, rawEventJson);
    Map<String, Object> response;
    try {
      response =
          oauthClient.withAccessToken(
              token -> executeVerification(token, headers, verificationRequest));
    } catch (PayPalUnauthorizedException exception) {
      throw new PayPalWebhookVerificationException(
          "PayPal rejected the webhook verification token twice", 401, null, exception);
    }

    String status = response == null ? null : stringValue(response.get("verification_status"));
    boolean verified = "SUCCESS".equalsIgnoreCase(status);
    if (!verified) {
      log.warn(
          "PayPal rejected webhook signature: transmissionId={} verificationStatus={}",
          headers.transmissionId(),
          status);
    }
    return verified;
  }

  private Map<String, Object> executeVerification(
      String token, PayPalWebhookHeaders headers, byte[] verificationRequest) {
    try {
      return restClient
          .post()
          .uri(VERIFY_PATH)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .headers(httpHeaders -> httpHeaders.setBearerAuth(token))
          .body(verificationRequest)
          .retrieve()
          .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    } catch (RestClientResponseException exception) {
      int status = exception.getStatusCode().value();
      String debugId = debugId(exception);
      log.warn(
          "PayPal webhook verification failed: transmissionId={} httpStatus={} debugId={}",
          headers.transmissionId(),
          status,
          debugId);
      if (status == 401) {
        throw new PayPalUnauthorizedException("PayPal rejected the webhook verification token");
      }
      throw new PayPalWebhookVerificationException(
          "PayPal webhook verification failed with HTTP " + status, status, debugId, exception);
    } catch (RestClientException exception) {
      log.warn(
          "PayPal webhook verification was unreachable: transmissionId={}",
          headers.transmissionId());
      throw new PayPalWebhookVerificationException(
          "PayPal webhook verification was unreachable", null, null, exception);
    }
  }

  private byte[] buildVerificationRequest(
      PayPalWebhookHeaders headers, String webhookId, byte[] rawEventJson) {
    ByteArrayOutputStream output = new ByteArrayOutputStream(rawEventJson.length + 512);
    try {
      output.write("{\"transmission_id\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(headers.transmissionId()));
      output.write(",\"transmission_time\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(headers.transmissionTime()));
      output.write(",\"cert_url\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(headers.certUrl()));
      output.write(",\"auth_algo\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(headers.authAlgorithm()));
      output.write(",\"transmission_sig\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(headers.transmissionSignature()));
      output.write(",\"webhook_id\":".getBytes(StandardCharsets.UTF_8));
      output.write(objectMapper.writeValueAsBytes(webhookId));
      output.write(",\"webhook_event\":".getBytes(StandardCharsets.UTF_8));
      output.write(rawEventJson);
      output.write('}');
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to assemble the PayPal verification request", exception);
    }
    return output.toByteArray();
  }

  private static String debugId(RestClientResponseException exception) {
    if (exception.getResponseHeaders() == null) {
      return "none";
    }
    String value = exception.getResponseHeaders().getFirst(DEBUG_ID_HEADER);
    return value == null || value.isBlank() ? "none" : value;
  }

  private static String stringValue(Object value) {
    return value instanceof String string ? string : null;
  }
}
