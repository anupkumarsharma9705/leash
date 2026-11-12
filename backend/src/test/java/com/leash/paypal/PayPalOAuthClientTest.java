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
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class PayPalOAuthClientTest {

  private static final String TOKEN_PATH = "/v1/oauth2/token";
  private static final String TOKEN_ONE = "A21AA-test-token-one";
  private static final String TOKEN_TWO = "A21AA-test-token-two";
  private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");

  private WireMockServer paypal;
  private MutableClock clock;

  @BeforeEach
  void startWireMock() {
    paypal = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    paypal.start();
    clock = new MutableClock(START);
  }

  @AfterEach
  void stopWireMock() {
    paypal.stop();
  }

  @Test
  void fetchesTokenWithBasicAuthAndClientCredentialsGrant() {
    paypal.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse(TOKEN_ONE, 3600)));

    String token = client(validProperties(), Duration.ofSeconds(2)).accessToken();

    assertThat(token).isEqualTo(TOKEN_ONE);
    String expectedBasic =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
    paypal.verify(
        postRequestedFor(urlEqualTo(TOKEN_PATH))
            .withHeader("Authorization", equalTo(expectedBasic))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withRequestBody(equalTo("grant_type=client_credentials")));
  }

  @Test
  void cachesTokenUntilRefreshWindow() {
    paypal.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse(TOKEN_ONE, 3600)));
    PayPalOAuthClient client = client(validProperties(), Duration.ofSeconds(2));

    String first = client.accessToken();
    clock.advanceSeconds(1000);
    String second = client.accessToken();

    assertThat(first).isEqualTo(TOKEN_ONE);
    assertThat(second).isEqualTo(TOKEN_ONE);
    paypal.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  @Test
  void refreshesTokenAtExpiresInMinusSixtySeconds() {
    paypal.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse(TOKEN_ONE, 61)));
    PayPalOAuthClient client = client(validProperties(), Duration.ofSeconds(2));

    assertThat(client.accessToken()).isEqualTo(TOKEN_ONE);

    // expires_in 61 means the token is only fresh for one second.
    clock.advanceSeconds(1);
    paypal.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse(TOKEN_TWO, 3600)));

    assertThat(client.accessToken()).isEqualTo(TOKEN_TWO);
    paypal.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  @Test
  void refreshesAndRetriesOnceAfterUnauthorized() {
    stubTwoTokens();
    PayPalOAuthClient client = client(validProperties(), Duration.ofSeconds(2));
    AtomicInteger attempts = new AtomicInteger();

    String result =
        client.withAccessToken(token -> {
          if (attempts.incrementAndGet() == 1) {
            throw new PayPalUnauthorizedException("resource rejected token " + token);
          }
          return "ok:" + token;
        });

    assertThat(result).isEqualTo("ok:" + TOKEN_TWO);
    assertThat(attempts).hasValue(2);
    paypal.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  @Test
  void propagatesSecondUnauthorizedWithoutFurtherRetries() {
    stubTwoTokens();
    PayPalOAuthClient client = client(validProperties(), Duration.ofSeconds(2));
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() ->
            client.withAccessToken(token -> {
              attempts.incrementAndGet();
              throw new PayPalUnauthorizedException("still unauthorized");
            }))
        .isInstanceOf(PayPalUnauthorizedException.class)
        .hasMessage("still unauthorized");

    assertThat(attempts).hasValue(2);
    paypal.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  @Test
  void timesOutWhenPayPalDoesNotRespond() {
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(tokenResponse(TOKEN_ONE, 3600).withFixedDelay(2000)));

    assertThatThrownBy(() -> client(validProperties(), Duration.ofMillis(250)).accessToken())
        .isInstanceOf(PayPalOAuthException.class)
        .hasMessage("PayPal OAuth token request failed")
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
  }

  @Test
  void rejectsMissingCredentialsWithoutCallingPayPal() {
    PayPalProperties properties =
        new PayPalProperties(
            "",
            "",
            "https://api-m.sandbox.paypal.com",
            "",
            false,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2));

    assertThatThrownBy(() -> client(properties, Duration.ofSeconds(2)).accessToken())
        .isInstanceOf(PayPalOAuthException.class)
        .hasMessageContaining("PAYPAL_CLIENT_ID");

    paypal.verify(0, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  @Test
  void rejectsTokenResponseWithoutAccessToken() {
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"expires_in\":3600}")));

    assertThatThrownBy(() -> client(validProperties(), Duration.ofSeconds(2)).accessToken())
        .isInstanceOf(PayPalOAuthException.class)
        .hasMessageContaining("did not contain an access_token");
  }

  @Test
  void rejectsTokenResponseWithInvalidExpiry() {
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"x\",\"expires_in\":0}")));

    assertThatThrownBy(() -> client(validProperties(), Duration.ofSeconds(2)).accessToken())
        .isInstanceOf(PayPalOAuthException.class)
        .hasMessageContaining("invalid expires_in");
  }

  @Test
  void concurrentCallersShareOneTokenRequest() throws Exception {
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .willReturn(tokenResponse(TOKEN_ONE, 3600).withFixedDelay(100)));
    PayPalOAuthClient client = client(validProperties(), Duration.ofSeconds(2));

    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Callable<String>> tasks =
          IntStream.range(0, 8).mapToObj(index -> (Callable<String>) client::accessToken).toList();
      List<Future<String>> futures = executor.invokeAll(tasks);
      for (Future<String> future : futures) {
        assertThat(future.get()).isEqualTo(TOKEN_ONE);
      }
    } finally {
      executor.shutdownNow();
    }

    paypal.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
  }

  private void stubTwoTokens() {
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("token-refresh")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(tokenResponse(TOKEN_ONE, 3600))
            .willSetStateTo("second-token"));
    paypal.stubFor(
        post(urlEqualTo(TOKEN_PATH))
            .inScenario("token-refresh")
            .whenScenarioStateIs("second-token")
            .willReturn(tokenResponse(TOKEN_TWO, 3600)));
  }

  private PayPalOAuthClient client(PayPalProperties properties, Duration readTimeout) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(1));
    requestFactory.setReadTimeout(readTimeout);
    RestClient restClient =
        RestClient.builder().requestFactory(requestFactory).baseUrl(paypal.baseUrl()).build();
    return new PayPalOAuthClient(restClient, properties, clock);
  }

  private static PayPalProperties validProperties() {
    return new PayPalProperties(
        "client-id",
        "client-secret",
        "https://api-m.sandbox.paypal.com",
        "webhook-id",
        false,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2));
  }

  private static ResponseDefinitionBuilder tokenResponse(String token, long expiresInSeconds) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(
            "{\"access_token\":\""
                + token
                + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                + expiresInSeconds
                + "}");
  }

  /** Test clock whose instant can be advanced to exercise the refresh window. */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("test clock is fixed to UTC");
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
