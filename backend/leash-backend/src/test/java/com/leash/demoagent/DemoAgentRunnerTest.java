package com.leash.demoagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DemoAgentRunnerTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC);
  private static final DemoBudget BUDGET = new DemoBudget(200_000, 1_200, 15_000, 183_800);

  private final DemoInvoiceLoader invoices = new DemoInvoiceLoader(new ObjectMapper());

  @Test
  void runsEveryInvoiceInOrderAndReportsTheDecisions() {
    FakeLeashClient client =
        new FakeLeashClient(
            payment("APPROVED", "APPROVE"),
            payment("PENDING_APPROVAL", "ESCALATE"),
            payment("DENIED", "DENY", "PAYEE_NOT_ALLOWED"));
    DemoAgentRunner runner = new DemoAgentRunner(client, invoices, CLOCK);

    DemoRunResult result = runner.run(DemoMode.SCRIPTED);

    assertThat(result.mode()).isEqualTo(DemoMode.SCRIPTED);
    assertThat(result.startedAt()).isNotNull();
    assertThat(result.finishedAt()).isNotNull();
    assertThat(result.scenes())
        .extracting(DemoSceneResult::invoiceId)
        .containsExactly("inv-1201", "inv-1502", "inv-9003");
    assertThat(result.scenes())
        .extracting(DemoSceneResult::outcome)
        .containsExactly("APPROVE", "ESCALATE", "DENY");
    assertThat(result.scenes().get(2).reasons())
        .extracting(DemoPayment.DemoReason::code)
        .containsExactly("PAYEE_NOT_ALLOWED");
    assertThat(result.summary())
        .contains("Processed 3 invoices: 1 approved, 1 escalated, 1 denied")
        .contains("Denied because PAYEE_NOT_ALLOWED")
        .contains("Remaining weekly budget: $1,838.00");
    assertThat(client.budgetReads).isEqualTo(2);
  }

  @Test
  void recordsAFailedSceneAndStillRunsTheRest() {
    FakeLeashClient client =
        new FakeLeashClient(
            payment("APPROVED", "APPROVE"),
            new DemoClientException(502, "DEMO_UPSTREAM_FAILED", "Leash is unavailable"),
            payment("DENIED", "DENY", "PAYEE_NOT_ALLOWED"));
    DemoAgentRunner runner = new DemoAgentRunner(client, invoices, CLOCK);

    DemoRunResult result = runner.run(DemoMode.SCRIPTED);

    assertThat(result.scenes()).hasSize(3);
    assertThat(result.scenes().get(1).decisionReceived()).isFalse();
    assertThat(result.scenes().get(1).errorCode()).isEqualTo("DEMO_UPSTREAM_FAILED");
    assertThat(result.summary())
        .contains("1 approved, 0 escalated, 1 denied, 1 failed")
        .contains("Denied because PAYEE_NOT_ALLOWED");
  }

  @Test
  void llmModeFailsClosedWithoutTouchingTheApi() {
    FakeLeashClient client =
        new FakeLeashClient(
            payment("APPROVED", "APPROVE"),
            payment("PENDING_APPROVAL", "ESCALATE"),
            payment("DENIED", "DENY", "PAYEE_NOT_ALLOWED"));
    DemoAgentRunner runner = new DemoAgentRunner(client, invoices, CLOCK);

    assertThatThrownBy(() -> runner.run(DemoMode.LLM))
        .isInstanceOf(DemoLlmNotConfiguredException.class);
    assertThat(client.budgetReads).isZero();
  }

  @Test
  void formatsMoneyFromIntegerMinorUnitsOnly() {
    assertThat(DemoAgentRunner.money(0)).isEqualTo("$0.00");
    assertThat(DemoAgentRunner.money(5)).isEqualTo("$0.05");
    assertThat(DemoAgentRunner.money(1_200)).isEqualTo("$12.00");
    assertThat(DemoAgentRunner.money(123_456_789)).isEqualTo("$1,234,567.89");
    assertThat(DemoAgentRunner.money(-5)).isEqualTo("-$0.05");
  }

  private static DemoPayment payment(String state, String outcome, String... reasonCodes) {
    List<DemoPayment.DemoReason> reasons =
        java.util.Arrays.stream(reasonCodes)
            .map(code -> new DemoPayment.DemoReason(code, code))
            .toList();
    return new DemoPayment(UUID.randomUUID(), state, outcome, reasons, BUDGET, false);
  }

  private static final class FakeLeashClient implements DemoLeashClient {

    private final Deque<Object> responses;
    private int budgetReads;

    private FakeLeashClient(Object... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public DemoBudget checkBudget() {
      budgetReads++;
      return BUDGET;
    }

    @Override
    public DemoPayment payInvoice(DemoInvoice invoice) {
      Object next = responses.removeFirst();
      if (next instanceof DemoClientException exception) {
        throw exception;
      }
      return (DemoPayment) next;
    }
  }
}
