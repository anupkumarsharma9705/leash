package com.leash.demoagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI-compatible chat-completions adapter with tool calling.
 *
 * <p>This is the thin-client fallback allowed by {@code AGENTS.md} §4 when Spring AI's installed
 * version cannot provide the same port. It speaks the common {@code /chat/completions} shape used
 * by DeepSeek, OpenAI, and compatible gateways. The API key stays in this adapter and is never sent
 * to the demo agent or logged.
 */
@Component
@Profile("demo")
public class OpenAiCompatibleAgentModelClient implements AgentModelClient {

  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAgentModelClient.class);

  private final LlmProperties properties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public OpenAiCompatibleAgentModelClient(LlmProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.timeout());
    requestFactory.setReadTimeout(properties.timeout());
    this.restClient = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public boolean isConfigured() {
    return properties.isConfigured();
  }

  @Override
  public ModelTurn complete(List<ModelMessage> messages, List<ModelTool> tools) {
    if (!isConfigured()) {
      throw new AgentModelException("LLM mode is not configured");
    }

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", properties.model());
    request.put("temperature", properties.temperature());
    request.put("messages", messages.stream().map(this::messageJson).toList());
    request.put("tools", tools.stream().map(this::toolJson).toList());
    request.put("tool_choice", "auto");

    String raw;
    try {
      raw =
          restClient
              .post()
              .uri(properties.chatCompletionsUrl())
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(request)
              .retrieve()
              .body(String.class);
    } catch (RestClientResponseException exception) {
      log.warn("LLM request failed: httpStatus={}", exception.getStatusCode().value());
      throw new AgentModelException(
          "LLM request failed with HTTP " + exception.getStatusCode().value(), exception);
    } catch (RestClientException exception) {
      log.warn("LLM endpoint was unreachable");
      throw new AgentModelException("LLM endpoint is unreachable", exception);
    }

    return parse(raw);
  }

  private ModelTurn parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new AgentModelException("LLM returned an empty response");
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(raw);
    } catch (RuntimeException exception) {
      throw new AgentModelException("LLM returned invalid JSON", exception);
    }

    JsonNode choices = root.get("choices");
    if (choices == null || !choices.isArray() || choices.size() == 0) {
      throw new AgentModelException("LLM returned no choices");
    }

    JsonNode message = choices.get(0).path("message");
    String content = text(message.get("content"));
    List<ToolCall> calls = new ArrayList<>();
    JsonNode toolCalls = message.get("tool_calls");
    if (toolCalls != null && toolCalls.isArray()) {
      int index = 0;
      for (JsonNode call : toolCalls) {
        String id = text(call.get("id"));
        if (id == null) {
          id = "call-" + index;
        }
        JsonNode function = call.get("function");
        String name = function == null ? null : text(function.get("name"));
        if (name == null) {
          continue;
        }
        calls.add(new ToolCall(id, name, arguments(function.get("arguments"))));
        index++;
      }
    }
    return new ModelTurn(content, calls);
  }

  private String arguments(JsonNode node) {
    if (node == null || node.isNull()) {
      return "{}";
    }
    if (node.isString()) {
      return node.asString();
    }
    try {
      return objectMapper.writeValueAsString(node);
    } catch (RuntimeException exception) {
      return "{}";
    }
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull() || !node.isString()) {
      return null;
    }
    String value = node.asString();
    return value == null || value.isBlank() ? null : value;
  }

  private Map<String, Object> messageJson(ModelMessage message) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("role", message.role());
    if (message.content() != null) {
      json.put("content", message.content());
    }
    if (!message.toolCalls().isEmpty()) {
      json.put("tool_calls", message.toolCalls().stream().map(this::toolCallJson).toList());
    }
    if (message.toolCallId() != null) {
      json.put("tool_call_id", message.toolCallId());
    }
    return json;
  }

  private Map<String, Object> toolCallJson(ToolCall call) {
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", call.name());
    function.put("arguments", call.arguments());

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", call.id());
    json.put("type", "function");
    json.put("function", function);
    return json;
  }

  private Map<String, Object> toolJson(ModelTool tool) {
    Map<String, Object> function = new LinkedHashMap<>();
    function.put("name", tool.name());
    function.put("description", tool.description());
    function.put("parameters", tool.parameters());

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("type", "function");
    json.put("function", function);
    return json;
  }
}
