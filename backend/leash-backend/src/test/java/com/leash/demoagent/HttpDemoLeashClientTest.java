package com.leash.demoagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpDemoLeashClientTest {

  private static final String API_KEY = "lsh_test-only-key-not-a-secret";

  private WireMockServer leash;
  private DemoAgentCredentials credentials;
  private DemoProperties properties;
  private HttpDemoLeashClient client;

  @BeforeEach
  void startWireMock() {
    leash = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    leash.start();

    credentials = new DemoAgentCredentials();
    credentials.publish(new DemoAgentIdentity(UUID.randomUUID(), "Invoice Agent", API_KEY));
    properties =
        new DemoProperties(
            "Invoice Agent",
            200_000,
            5_000,
            List.of("alice@example.com"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2));

    client =
        new HttpDemoLeashClient(credentials, () -> leash.baseUrl(), properties, new ObjectMapper());
  }

  @AfterEach
  void stopWireMock() {
    if (leash.isRunning()) {
      leash.stop();
    }
  }

  @Test
  void readsTheBudgetWithTheAgentsOwnBearerKey() {
    leash.stubFor(get(urlEqualTo("/api/v1/budget")).willReturn(okJson(budgetJson())));

    DemoBudget budget = client.checkBudget();

    assertThat(budget.limitMinor()).isEqualTo(200_000);
    assertThat(budget.spentMinor()).isEqualTo(1_200);
    assertThat(budget.reservedMinor()).isEqualTo(15_000);
    assertThat(budget.remainingMinor()).isEqualTo(183_800);
    leash.verify(
        getRequestedFor(urlEqualTo("/api/v1/budget"))
            .withHeader("Authorization", equalTo("Bearer " + API_KEY)));
  }

  @Test
  void paysAnInvoiceWithItsStableIdempotencyKeyAndReadsTheReplayHeader() {
    leash.stubFor(post(urlEqualTo("/api/v1/payments")).willReturn(escalationResponse()));
    DemoInvoice invoice =
        new DemoInvoice(
            "inv-1502", "Weekly research", "alice@example.com", 15_000, "USD", "Invoice inv-1502");

    DemoPayment payment = client.payInvoice(invoice);

    assertThat(payment.paymentId())
        .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    assertThat(payment.state()).isEqualTo("PENDING_APPROVAL");
    assertThat(payment.outcome()).isEqualTo("ESCALATE");
    assertThat(payment.reasons()).isEmpty();
    assertThat(payment.budget().remainingMinor()).isEqualTo(183_800);
    assertThat(payment.replayed()).isTrue();
    leash.verify(
        postRequestedFor(urlEqualTo("/api/v1/payments"))
            .withHeader("Authorization", equalTo("Bearer " + API_KEY))
            .withHeader("Idempotency-Key", equalTo("invoice-inv-1502"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
                equalToJson(
                    """
                    {"payeeEmail":"alice@example.com","amountMinor":15000,
                     "currency":"USD","memo":"Invoice inv-1502"}
                    """)));
  }

  @Test
  void mapsAProblemDetailIntoTheAgentsError() {
    leash.stubFor(
        post(urlEqualTo("/api/v1/payments"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        """
                        {"type":"about:blank","title":"Idempotency key reused","status":422,
                         "detail":"The idempotency key was used with a different body",
                         "code":"IDEMPOTENCY_KEY_REUSED"}
                        """)));
    DemoInvoice invoice =
        new DemoInvoice("inv-1", "One", "alice@example.com", 100, "USD", "Invoice inv-1");

    assertThatThrownBy(() -> client.payInvoice(invoice))
        .isInstanceOfSatisfying(
            DemoClientException.class,
            exception -> {
              assertThat(exception.httpStatus()).isEqualTo(422);
              assertThat(exception.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
              assertThat(exception.getMessage())
                  .isEqualTo("The idempotency key was used with a different body");
            });

    leash.verify(
        postRequestedFor(urlEqualTo("/api/v1/payments"))
            .withHeader("Idempotency-Key", equalTo("invoice-inv-1")));
  }

  @Test
  void reportsAnUnreachableLeashWithAStableCode() {
    String url = leash.baseUrl();
    HttpDemoLeashClient offline =
        new HttpDemoLeashClient(credentials, () -> url, properties, new ObjectMapper());
    leash.stop();

    assertThatThrownBy(offline::checkBudget)
        .isInstanceOfSatisfying(
            DemoClientException.class,
            exception -> {
              assertThat(exception.httpStatus()).isNull();
              assertThat(exception.code()).isEqualTo("DEMO_UPSTREAM_UNAVAILABLE");
            });
  }

  @Test
  void refusesToCallLeashBeforeTheAgentIsProvisioned() {
    HttpDemoLeashClient unprovisioned =
        new HttpDemoLeashClient(
            new DemoAgentCredentials(),
            () -> leash.baseUrl(),
            new DemoProperties(
                "Invoice Agent",
                200_000,
                5_000,
                List.of("a@example.com"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)),
            new ObjectMapper());

    assertThatThrownBy(unprovisioned::checkBudget).isInstanceOf(DemoAgentNotReadyException.class);
  }

  private static ResponseDefinitionBuilder okJson(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  private static ResponseDefinitionBuilder escalationResponse() {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withHeader("Idempotent-Replayed", "true")
        .withBody(
            """
            {
              "paymentId":"11111111-1111-1111-1111-111111111111",
              "state":"PENDING_APPROVAL",
              "decision":{"outcome":"ESCALATE","reasons":[]},
              "budget":{"limitMinor":200000,"spentMinor":1200,
                        "reservedMinor":15000,"remainingMinor":183800}
            }
            """);
  }

  private static String budgetJson() {
    return """
    {"limitMinor":200000,"spentMinor":1200,"reservedMinor":15000,"remainingMinor":183800}
    """;
  }
}
