package com.leash.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.leash.common.money.Money;
import com.leash.common.money.SupportedCurrency;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class PayPalPayoutsAdapterTest {

  private static final String TOKEN_PATH = "/v1/oauth2/token";
  private static final String PAYOUTS_PATH = "/v1/payments/payouts";
  private static final String BATCH_ID = "BATCH-123";
  private static final String PAYMENT_ID = "pay_123";
  private static final String TOKEN_ONE = "A21AA-token-one";
  private static final String TOKEN_TWO = "A21AA-token-two";

  private WireMockServer paypal;
  private Clock clock;

  @BeforeEach
  void startWireMock() {
    paypal = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paypal.start();
    clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    stubToken(TOKEN_ONE);
  }

  @AfterEach
  void stopWireMock() {
    paypal.stop();
  }

  @Test
  void submitsOnePayoutWithIdempotencyKeys() {
    paypal.stubFor(post(urlEqualTo(PAYOUTS_PATH)).willReturn(createdResponse()));

    PayoutSubmission submission = adapter().submit(request());

    assertThat(submission.payoutBatchId()).isEqualTo(BATCH_ID);
    assertThat(submission.rawBatchStatus()).isEqualTo("PENDING");
    paypal.verify(
        postRequestedFor(urlEqualTo(PAYOUTS_PATH))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN_ONE))
            .withHeader("PayPal-Request-Id", equalTo(PAYMENT_ID))
            .withHeader("Content-Type", containing("application/json"))
            .withRequestBody(
                equalToJson(
                    """
                    {
                      "sender_batch_header": {
                        "sender_batch_id": "leash-pay_123",
                        "recipient_type": "EMAIL"
                      },
                      "items": [
                        {
                          "recipient_type": "EMAIL",
                          "amount": {"currency": "USD", "value": "12.00"},
                          "receiver": "alice@example.com",
                          "sender_item_id": "pay_123",
                          "note": "Invoice inv-7"
                        }
                      ]
                    }
                    """)));
  }

  @Test
  void looksUpBatchAndItemStatus() {
    paypal.stubFor(
        get(urlPathEqualTo(PAYOUTS_PATH + "/" + BATCH_ID))
            .willReturn(batchResponse("SUCCESS", "SUCCESS")));

    PayPalPayoutStatus status = adapter().lookup(BATCH_ID);

    assertThat(status.payoutBatchId()).isEqualTo(BATCH_ID);
    assertThat(status.rawBatchStatus()).isEqualTo("SUCCESS");
    assertThat(status.rawItemStatus()).isEqualTo("SUCCESS");
    paypal.verify(
        getRequestedFor(urlPathEqualTo(PAYOUTS_PATH + "/" + BATCH_ID))
            .withHeader("Authorization", equalTo("Bearer " + TOKEN_ONE))
            .withQueryParam("page_size", equalTo("100")));
  }

  @Test
  void retriesServerErrorThenSucceedsWithSameIdempotencyKey() {
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-500")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500).withHeader("PayPal-Debug-Id", "debug-500"))
            .willSetStateTo("ok"));
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-500")
            .whenScenarioStateIs("ok")
            .willReturn(createdResponse()));

    PayoutSubmission submission = adapter().submit(request());

    assertThat(submission.payoutBatchId()).isEqualTo(BATCH_ID);
    paypal.verify(
        2,
        postRequestedFor(urlEqualTo(PAYOUTS_PATH))
            .withHeader("PayPal-Request-Id", equalTo(PAYMENT_ID)));
  }

  @Test
  void retriesRateLimitThenSucceeds() {
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-429")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("PayPal-Debug-Id", "debug-429"))
            .willSetStateTo("ok"));
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-429")
            .whenScenarioStateIs("ok")
            .willReturn(createdResponse()));

    assertThat(adapter().submit(request()).payoutBatchId()).isEqualTo(BATCH_ID);
    paypal.verify(2, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
  }

  @Test
  void givesUpAfterThreeAttempts() {
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .willReturn(aResponse().withStatus(500).withHeader("PayPal-Debug-Id", "debug-500")));

    assertThatThrownBy(() -> adapter().submit(request()))
        .isInstanceOf(PayPalPayoutException.class)
        .hasMessageContaining("debug-500");

    paypal.verify(3, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
  }

  @Test
  void doesNotRetryClientErrorsAndExposesDebugId() {
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .willReturn(aResponse().withStatus(400).withHeader("PayPal-Debug-Id", "debug-400")));

    assertThatThrownBy(() -> adapter().submit(request()))
        .isInstanceOfSatisfying(
            PayPalPayoutException.class,
            exception -> {
              assertThat(exception.httpStatus()).isEqualTo(400);
              assertThat(exception.debugId()).isEqualTo("debug-400");
            });

    paypal.verify(1, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
  }

  @Test
  void logsPayPalDebugIdOnEveryErrorResponse() {
    Logger logger = (Logger) LoggerFactory.getLogger(PayPalPayoutsAdapter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      paypal.stubFor(
          post(urlEqualTo(PAYOUTS_PATH))
              .willReturn(aResponse().withStatus(422).withHeader("PayPal-Debug-Id", "debug-422")));

      assertThatThrownBy(() -> adapter().submit(request()))
          .isInstanceOf(PayPalPayoutException.class);

      assertThat(appender.list)
          .anySatisfy(event ->
              assertThat(event.getFormattedMessage())
                  .contains("debugId=debug-422")
                  .contains("httpStatus=422"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void retriesTimeoutThenSucceeds() {
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-timeout")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(createdResponse().withFixedDelay(1000))
            .willSetStateTo("ok"));
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("retry-timeout")
            .whenScenarioStateIs("ok")
            .willReturn(createdResponse()));

    assertThat(adapter(Duration.ofMillis(250)).submit(request()).payoutBatchId())
        .isEqualTo(BATCH_ID);
    paypal.verify(2, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
  }

  @Test
  void refreshesTokenAndRetriesOnceOnUnauthorized() {
    paypal.resetAll();
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("tokens")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(tokenResponse(TOKEN_ONE))
            .willSetStateTo("second-token"));
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("tokens")
            .whenScenarioStateIs("second-token")
            .willReturn(tokenResponse(TOKEN_TWO)));
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("payout-401")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(401).withHeader("PayPal-Debug-Id", "debug-401"))
            .willSetStateTo("authorized"));
    paypal.stubFor(
        post(urlEqualTo(PAYOUTS_PATH))
            .inScenario("payout-401")
            .whenScenarioStateIs("authorized")
            .willReturn(createdResponse()));

    PayoutSubmission submission = adapter().submit(request());

    assertThat(submission.payoutBatchId()).isEqualTo(BATCH_ID);
    paypal.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    paypal.verify(2, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));

    List<LoggedRequest> payoutRequests = paypal.findAll(postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
    assertThat(payoutRequests)
        .extracting(request -> request.getHeader("Authorization"))
        .containsExactly("Bearer " + TOKEN_ONE, "Bearer " + TOKEN_TWO);
  }

  @Test
  void reportsMissingCredentialsAsATransientPayoutFailureWithoutCallingPayPal() {
    PayPalPayoutsAdapter adapter = adapterWithCredentials("", "");

    assertThatThrownBy(() -> adapter.submit(request()))
        .isInstanceOfSatisfying(
            PayPalPayoutException.class,
            exception -> {
              assertThat(exception.httpStatus()).isNull();
              assertThat(exception).hasRootCauseInstanceOf(PayPalOAuthException.class);
            });
    assertThatThrownBy(() -> adapter.lookup(BATCH_ID))
        .isInstanceOfSatisfying(
            PayPalPayoutException.class,
            exception -> {
              assertThat(exception.httpStatus()).isNull();
              assertThat(exception).hasRootCauseInstanceOf(PayPalOAuthException.class);
            });

    paypal.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    paypal.verify(0, postRequestedFor(urlEqualTo(PAYOUTS_PATH)));
    paypal.verify(0, getRequestedFor(urlPathEqualTo(PAYOUTS_PATH + "/" + BATCH_ID)));
  }

  private PayPalPayoutsAdapter adapter() {
    return adapter(Duration.ofSeconds(2));
  }

  private PayPalPayoutsAdapter adapter(Duration readTimeout) {
    return adapter(readTimeout, "client-id", "client-secret");
  }

  private PayPalPayoutsAdapter adapterWithCredentials(String clientId, String clientSecret) {
    return adapter(Duration.ofSeconds(2), clientId, clientSecret);
  }

  private PayPalPayoutsAdapter adapter(Duration readTimeout, String clientId, String clientSecret) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(readTimeout);
    RestClient restClient =
        RestClient.builder().requestFactory(requestFactory).baseUrl(paypal.baseUrl()).build();
    PayPalProperties properties =
        new PayPalProperties(
            clientId,
            clientSecret,
            "https://api-m.sandbox.paypal.com",
            "webhook-id",
            false,
            Duration.ofSeconds(1),
            readTimeout);
    PayPalOAuthClient oauthClient = new PayPalOAuthClient(restClient, properties, clock);
    return new PayPalPayoutsAdapter(restClient, oauthClient, duration -> {});
  }

  private static PayoutRequest request() {
    return new PayoutRequest(
        PAYMENT_ID, "alice@example.com", new Money(1200, SupportedCurrency.USD), "Invoice inv-7");
  }

  private void stubToken(String token) {
    paypal.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse(token)));
  }

  private static ResponseDefinitionBuilder tokenResponse(String token) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(
            "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
  }

  private static ResponseDefinitionBuilder createdResponse() {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(
            """
            {
              "batch_header": {
                "payout_batch_id": "BATCH-123",
                "batch_status": "PENDING",
                "sender_batch_header": {"sender_batch_id": "leash-pay_123"}
              }
            }
            """);
  }

  private static ResponseDefinitionBuilder batchResponse(String batchStatus, String itemStatus) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(
            """
            {
              "total_items": 1,
              "total_pages": 1,
              "batch_header": {
                "payout_batch_id": "BATCH-123",
                "batch_status": "%s",
                "sender_batch_header": {"sender_batch_id": "leash-pay_123"}
              },
              "items": [
                {
                  "payout_item_id": "ITEM-1",
                  "transaction_status": "%s",
                  "payout_batch_id": "BATCH-123",
                  "payout_item": {
                    "amount": {"currency": "USD", "value": "12.00"},
                    "receiver": "alice@example.com",
                    "sender_item_id": "pay_123"
                  }
                }
              ]
            }
            """
                .formatted(batchStatus, itemStatus));
  }
}
