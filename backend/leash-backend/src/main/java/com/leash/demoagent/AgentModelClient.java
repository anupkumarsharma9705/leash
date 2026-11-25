package com.leash.demoagent;

import java.util.List;
import java.util.Map;

/**
 * Port for an OpenAI-compatible tool-calling model.
 *
 * <p>The model never sees PayPal credentials and never calls Leash directly. It chooses among the
 * tools offered here; every resulting HTTP call runs through {@link DemoLeashClient} with the demo
 * agent's own key, so policy remains authoritative.
 */
public interface AgentModelClient {

  boolean isConfigured();

  ModelTurn complete(List<ModelMessage> messages, List<ModelTool> tools);

  /** One chat message in the conversation sent back to the provider. */
  record ModelMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public ModelMessage {
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("role is required");
      }
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ModelMessage system(String content) {
      return new ModelMessage("system", content, List.of(), null);
    }

    public static ModelMessage user(String content) {
      return new ModelMessage("user", content, List.of(), null);
    }

    public static ModelMessage assistant(String content, List<ToolCall> toolCalls) {
      return new ModelMessage("assistant", content, toolCalls, null);
    }

    public static ModelMessage tool(String toolCallId, String content) {
      return new ModelMessage("tool", content, List.of(), toolCallId);
    }
  }

  /** One function exposed to the model. */
  record ModelTool(String name, String description, Map<String, Object> parameters) {

    public ModelTool {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("tool name is required");
      }
      parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
  }

  /** One assistant turn: either prose, tool calls, or both. */
  record ModelTurn(String content, List<ToolCall> toolCalls) {

    public ModelTurn {
      toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
      return !toolCalls.isEmpty();
    }
  }

  /** One requested function call. Arguments stay as the provider's JSON string. */
  record ToolCall(String id, String name, String arguments) {

    public ToolCall {
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("tool call id is required");
      }
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("tool call name is required");
      }
      arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
  }
}
