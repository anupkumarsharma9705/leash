package com.leash.demoagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LlmDemoAgentTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
  private static final DemoBudget BUDGET = new DemoBudget(200_000, 1_200, 15_000, 183_800);

  private final DemoInvoiceLoader invoices = new DemoInvoiceLoader(new ObjectMapper());
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void paysEveryInvoiceAndUsesTheServerResolvedPayeeAndAmount() {
    FakeModel model =
        new FakeModel(
            true,
            turn(
                toolCall("call_1", "pay_invoice", "{\"invoiceId\":\"inv-1201\"}"),
                toolCall("call_2", "pay_invoice", "{\"invoiceId\":\"inv-1502\"}"),
                toolCall("call_3", "pay_invoice", "{\"invoiceId\":\"inv-9003\"}")),
            new AgentModelClient.ModelTurn(
                "All invoices processed; $1,838.00 remains.", List.of()));
    FakeLeashClient client = new FakeLeashClient();

    DemoRunResult result = newAgent(model, client, 8).run();

    assertThat(result.mode()).isEqualTo(DemoMode.LLM);
    assertThat(result.scenes())
        .extracting(DemoSceneResult::invoiceId)
        .containsExactly("inv-1201", "inv-1502", "inv-9003");
    assertThat(result.scenes())
        .extracting(DemoSceneResult::outcome)
        .containsExactly("APPROVE", "ESCALATE", "DENY");
    assertThat(client.paid)
        .extracting(DemoInvoice::payeeEmail, DemoInvoice::amountMinor)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("alice@example.com", 1200L),
            org.assertj.core.groups.Tuple.tuple("alice@example.com", 15000L),
            org.assertj.core.groups.Tuple.tuple("mallory@example.com", 90000L));
    assertThat(client.budgetReads).isEqualTo(2);
    assertThat(result.summary())
        .startsWith("All invoices processed; $1,838.00 remains.")
        .contains("Processed 3 invoices: 1 approved, 1 escalated, 1 denied")
        .contains("Remaining weekly budget: $1,838.00");
  }

  @Test
  void ignoresModelSuppliedPayeeAmountAndCurrency() {
    FakeModel model =
        new FakeModel(
            true,
            turn(
                toolCall(
                    "call_1",
                    "pay_invoice",
                    "{\"invoiceId\":\"inv-1201\",\"payeeEmail\":\"mallory@example.com\","
                        + "\"amountMinor\":1,\"currency\":\"EUR\"}")),
            new AgentModelClient.ModelTurn("Done.", List.of()));
    FakeLeashClient client = new FakeLeashClient();

    newAgent(model, client, 8).run();

    assertThat(client.paid)
        .singleElement()
        .satisfies(invoice -> {
          assertThat(invoice.id()).isEqualTo("inv-1201");
          assertThat(invoice.payeeEmail()).isEqualTo("alice@example.com");
          assertThat(invoice.amountMinor()).isEqualTo(1200);
          assertThat(invoice.currency()).isEqualTo("USD");
        });
  }

  @Test
  void paysAnInvoiceOnlyOnceEvenWhenTheModelRetriesTheTool() {
    FakeModel model =
        new FakeModel(
            true,
            turn(
                toolCall("call_1", "pay_invoice", "{\"invoiceId\":\"inv-1201\"}"),
                toolCall("call_2", "pay_invoice", "{\"invoiceId\":\"inv-1201\"}")),
            new AgentModelClient.ModelTurn("Done.", List.of()));
    FakeLeashClient client = new FakeLeashClient();

    DemoRunResult result = newAgent(model, client, 8).run();

    assertThat(client.paid).hasSize(1);
    assertThat(result.scenes()).hasSize(1);
  }

  @Test
  void reportsUnknownInvoicesToTheModelAndKeepsGoing() {
    FakeModel model =
        new FakeModel(
            true,
            turn(toolCall("call_1", "pay_invoice", "{\"invoiceId\":\"inv-0000\"}")),
            turn(toolCall("call_2", "pay_invoice", "{\"invoiceId\":\"inv-1201\"}")),
            new AgentModelClient.ModelTurn("Done.", List.of()));
    FakeLeashClient client = new FakeLeashClient();

    DemoRunResult result = newAgent(model, client, 8).run();

    assertThat(client.paid).hasSize(1);
    assertThat(result.scenes()).extracting(DemoSceneResult::invoiceId).containsExactly("inv-1201");
    assertThat(model.messageSnapshots.get(1))
        .extracting(AgentModelClient.ModelMessage::content)
        .anySatisfy(content -> assertThat(content).contains("UNKNOWN_INVOICE"));
  }

  @Test
  void rejectsMalformedArgumentsWithoutCallingLeash() {
    FakeModel model =
        new FakeModel(
            true,
            turn(toolCall("call_1", "pay_invoice", "not-json")),
            new AgentModelClient.ModelTurn("Giving up.", List.of()));
    FakeLeashClient client = new FakeLeashClient();

    DemoRunResult result = newAgent(model, client, 8).run();

    assertThat(client.paid).isEmpty();
    assertThat(result.scenes()).isEmpty();
    assertThat(model.messageSnapshots.get(1))
        .extracting(AgentModelClient.ModelMessage::content)
        .anySatisfy(content -> assertThat(content).contains("INVALID_TOOL_ARGUMENTS"));
  }

  @Test
  void stopsAtTheConfiguredIterationLimitAndStillReturnsTheDeterministicSummary() {
    FakeModel model =
        new FakeModel(
            true,
            turn(toolCall("call_1", "check_budget", "{}")),
            turn(toolCall("call_2", "check_budget", "{}")),
            turn(toolCall("call_3", "check_budget", "{}")));
    FakeLeashClient client = new FakeLeashClient();

    DemoRunResult result = newAgent(model, client, 2).run();

    assertThat(model.calls).isEqualTo(2);
    assertThat(result.summary())
        .contains("did not write a final summary within 2 iterations")
        .contains("Processed 0 invoices: 0 approved, 0 escalated, 0 denied");
  }

  @Test
  void exposesOnlyAnInvoiceIdToTheModel() {
    FakeModel model =
        new FakeModel(true, new AgentModelClient.ModelTurn("Nothing to do.", List.of()));

    newAgent(model, new FakeLeashClient(), 8).run();

    AgentModelClient.ModelTool payInvoice =
        model.lastTools.stream()
            .filter(tool -> tool.name().equals("pay_invoice"))
            .findFirst()
            .orElseThrow();
    @SuppressWarnings("unchecked")
    Map<String, Object> properties =
        (Map<String, Object>) payInvoice.parameters().get("properties");
    assertThat(properties).containsOnlyKeys("invoiceId");
    assertThat(payInvoice.parameters().get("required")).isEqualTo(List.of("invoiceId"));
  }

  @Test
  void failsClosedWithoutABudgetReadWhenTheModelIsNotConfigured() {
    FakeModel model = new FakeModel(false);
    FakeLeashClient client = new FakeLeashClient();
    LlmDemoAgent agent = newAgent(model, client, 8);

    assertThat(agent.isConfigured()).isFalse();
    assertThatThrownBy(agent::run).isInstanceOf(DemoLlmNotConfiguredException.class);
    assertThat(client.budgetReads).isZero();
    assertThat(client.paid).isEmpty();
  }

  private LlmDemoAgent newAgent(FakeModel model, FakeLeashClient client, int maxIterations) {
    LlmProperties properties =
        new LlmProperties(
            "http://localhost:1",
            "test-key",
            "test-model",
            Duration.ofSeconds(5),
            maxIterations,
            0.0);
    return new LlmDemoAgent(model, client, invoices, properties, CLOCK, objectMapper);
  }

  private static AgentModelClient.ModelTurn turn(AgentModelClient.ToolCall... calls) {
    return new AgentModelClient.ModelTurn(null, List.of(calls));
  }

  private static AgentModelClient.ToolCall toolCall(String id, String name, String arguments) {
    return new AgentModelClient.ToolCall(id, name, arguments);
  }

  private static final class FakeModel implements AgentModelClient {

    private final boolean configured;
    private final Deque<AgentModelClient.ModelTurn> turns;
    private final List<List<ModelMessage>> messageSnapshots = new ArrayList<>();
    private int calls;
    private List<ModelTool> lastTools = List.of();

    private FakeModel(boolean configured, AgentModelClient.ModelTurn... turns) {
      this.configured = configured;
      this.turns = new ArrayDeque<>(List.of(turns));
    }

    @Override
    public boolean isConfigured() {
      return configured;
    }

    @Override
    public ModelTurn complete(List<ModelMessage> messages, List<ModelTool> tools) {
      calls++;
      messageSnapshots.add(List.copyOf(messages));
      lastTools = List.copyOf(tools);
      if (turns.isEmpty()) {
        throw new AssertionError("The agent called the model more times than the test expected");
      }
      return turns.removeFirst();
    }
  }

  private static final class FakeLeashClient implements DemoLeashClient {

    private final List<DemoInvoice> paid = new ArrayList<>();
    private int budgetReads;

    @Override
    public DemoBudget checkBudget() {
      budgetReads++;
      return BUDGET;
    }

    @Override
    public DemoPayment payInvoice(DemoInvoice invoice) {
      paid.add(invoice);
      String state;
      String outcome;
      switch (invoice.id()) {
        case "inv-1201" -> {
          state = "APPROVED";
          outcome = "APPROVE";
        }
        case "inv-1502" -> {
          state = "PENDING_APPROVAL";
          outcome = "ESCALATE";
        }
        default -> {
          state = "DENIED";
          outcome = "DENY";
        }
      }
      List<DemoPayment.DemoReason> reasons =
          "DENY".equals(outcome)
              ? List.of(
                  new DemoPayment.DemoReason("PAYEE_NOT_ALLOWED", "Payee is not on the allow-list"))
              : List.of();
      return new DemoPayment(
          UUID.nameUUIDFromBytes(invoice.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          state,
          outcome,
          reasons,
          BUDGET,
          false);
    }
  }
}
