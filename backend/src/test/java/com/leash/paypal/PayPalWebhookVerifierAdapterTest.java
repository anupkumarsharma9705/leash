package com.leash.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class PayPalWebhookVerifierAdapterTest {

  private static final String TOKEN_PATH = "/v1/oauth2/token";
  private static final String VERIFY_PATH = "/v1/notifications/verify-webhook-signature";
  private static final String WEBHOOK_ID = "WH-TEST-123";
  private static final String TOKEN = "A21AA-webhook-token";
  private static final String RAW_EVENT =
      "{\n"
          + "  \"id\": \"WH-1\",\n"
          + "  \"event_type\": \"PAYMENT.PAYOUTS-ITEM.SUCCEEDED\",\n"
          + "  \"resource\": {\"payout_item_id\": \"ITEM-1\", \"sender_item_id\": \"pay_123\"}\n"
          + "}";

  private WireMockServer paypal;

  @BeforeEach
  void startWireMock() {
    paypal = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paypal.start();
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\""
                            + TOKEN
                            + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
  }

  @AfterEach
  void stopWireMock() {
    paypal.stop();
  }

  @Test
  void embedsRawEventJsonVerbatimInVerificationRequest() {
    paypal.stubFor(
        post(urlEqualTo(VERIFY_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"verification_status\":\"SUCCESS\"}")));

    boolean verified =
        verifier(WEBHOOK_ID).verify(headers(), RAW_EVENT.getBytes(StandardCharsets.UTF_8));

    assertThat(verified).isTrue();
    String expected =
        "{\"transmission_id\":\"tx-1\","
            + "\"transmission_time\":\"2026-11-10T00:00:00Z\","
            + "\"cert_url\":\"https://api.paypal.com/cert.pem\","
            + "\"auth_algo\":\"SHA256withRSA\","
            + "\"transmission_sig\":\"signature-value\","
            + "\"webhook_id\":\""
            + WEBHOOK_ID
            + "\","
            + "\"webhook_event\":"
            + RAW_EVENT
            + "}";
    paypal.verify(
        postRequestedFor(urlEqualTo(VERIFY_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(equalTo(expected)));
  }

  @Test
  void returnsFalseWhenPayPalRejectsSignature() {
    paypal.stubFor(
        post(urlEqualTo(VERIFY_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"verification_status\":\"FAILURE\"}")));

    boolean verified =
        verifier(WEBHOOK_ID).verify(headers(), RAW_EVENT.getBytes(StandardCharsets.UTF_8));

    assertThat(verified).isFalse();
  }

  @Test
  void returnsFalseWithoutCallingPayPalWhenHeadersAreIncomplete() {
    PayPalWebhookHeaders incomplete =
        new PayPalWebhookHeaders("tx-1", null, "signature", "cert", "SHA256withRSA");

    boolean verified = verifier(WEBHOOK_ID).verify(incomplete, RAW_EVENT.getBytes());

    assertThat(verified).isFalse();
    paypal.verify(0, postRequestedFor(urlEqualTo(VERIFY_PATH)));
  }

  @Test
  void refusesToVerifyWhenWebhookIdIsMissing() {
    assertThatThrownBy(
            () -> verifier("").verify(headers(), RAW_EVENT.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(PayPalWebhookVerificationException.class)
        .hasMessageContaining("PAYPAL_WEBHOOK_ID");
  }

  private static PayPalWebhookHeaders headers() {
    return new PayPalWebhookHeaders(
        "tx-1",
        "2026-11-10T00:00:00Z",
        "signature-value",
        "https://api.paypal.com/cert.pem",
        "SHA256withRSA");
  }

  private PayPalWebhookVerifierAdapter verifier(String webhookId) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(Duration.ofSeconds(2));
    RestClient restClient =
        RestClient.builder().requestFactory(requestFactory).baseUrl(paypal.baseUrl()).build();
    PayPalProperties properties =
        new PayPalProperties(
            "client-id",
            "client-secret",
            "https://api-m.sandbox.paypal.com",
            webhookId,
            false,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2));
    PayPalOAuthClient oauthClient =
        new PayPalOAuthClient(
            restClient,
            properties,
            Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    return new PayPalWebhookVerifierAdapter(
        restClient, oauthClient, properties, new ObjectMapper());
  }
}
