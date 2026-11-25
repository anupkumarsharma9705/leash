package com.leash.demoagent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiCompatibleAgentModelClientTest {

  private WireMockServer server;
  private OpenAiCompatibleAgentModelClient client;

  @BeforeEach
  void startWireMock() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    LlmProperties properties =
        new LlmProperties(
            server.baseUrl(), "test-key", "test-model", Duration.ofSeconds(5), 8, 0.0);
    client = new OpenAiCompatibleAgentModelClient(properties, new ObjectMapper());
  }

  @AfterEach
  void stopWireMock() {
    server.stop();
  }

  @Test
  void sendsTheToolSchemaAndParsesToolCalls() {
    server.stubFor(
        post(urlEqualTo("/chat/completions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_1","type":"function",
                        "function":{"name":"pay_invoice",
                        "arguments":"{\\"invoiceId\\":\\"inv-1201\\"}"}}]}}]}
                        """)));

    AgentModelClient.ModelTurn turn =
        client.complete(
            List.of(
                AgentModelClient.ModelMessage.system("system"),
                AgentModelClient.ModelMessage.user("pay")),
            List.of(
                new AgentModelClient.ModelTool(
                    "pay_invoice", "pay one invoice", Map.of("type", "object"))));

    assertThat(turn.hasToolCalls()).isTrue();
    assertThat(turn.toolCalls())
        .singleElement()
        .satisfies(call -> {
          assertThat(call.id()).isEqualTo("call_1");
          assertThat(call.name()).isEqualTo("pay_invoice");
          assertThat(call.arguments()).contains("inv-1201");
        });

    server.verify(
        postRequestedFor(urlEqualTo("/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer test-key"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
            .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("pay_invoice"))));
  }

  @Test
  void acceptsProviderArgumentsSerializedAsAnObject() {
    server.stubFor(
        post(urlEqualTo("/chat/completions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"choices":[{"message":{"role":"assistant","content":null,
                        "tool_calls":[{"id":"call_2","type":"function",
                        "function":{"name":"pay_invoice",
                        "arguments":{"invoiceId":"inv-1201"}}}]}}]}
                        """)));

    AgentModelClient.ModelTurn turn = client.complete(List.of(), List.of());

    assertThat(turn.toolCalls())
        .singleElement()
        .satisfies(call -> assertThat(call.arguments()).contains("\"invoiceId\""));
  }

  @Test
  void mapsHttpFailuresToASafeException() {
    server.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client.complete(List.of(), List.of()))
        .isInstanceOf(AgentModelException.class)
        .hasMessageContaining("500");
  }

  @Test
  void remainsDisabledUntilAllThreeSettingsArePresent() {
    LlmProperties blank = new LlmProperties("", "key", "model", Duration.ofSeconds(5), 8, 0.0);
    OpenAiCompatibleAgentModelClient disabled =
        new OpenAiCompatibleAgentModelClient(blank, new ObjectMapper());

    assertThat(disabled.isConfigured()).isFalse();
    assertThatThrownBy(() -> disabled.complete(List.of(), List.of()))
        .isInstanceOf(AgentModelException.class);
  }
}
