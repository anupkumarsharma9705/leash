package com.leash.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyEngineTest {

  private static final long HARD_CAP = 100_000L;
  private static final UUID AGENT_ID = UUID.fromString("0d54a0f4-8f5e-4d0b-9a3e-0b9b7d4a1f11");
  private static final Instant WEDNESDAY = Instant.parse("2026-11-11T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(WEDNESDAY, ZoneOffset.UTC);

  private final PolicyEngine engine = new PolicyEngine(HARD_CAP);

  @Test
  void approvesAmountAtTheAutoApproveThreshold() {
    PolicyDecision decision = engine.evaluate(context().amountMinor(10_000L).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.APPROVE);
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  void escalatesOneMinorUnitAboveTheAutoApproveThreshold() {
    PolicyDecision decision = engine.evaluate(context().amountMinor(10_001L).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.ESCALATE);
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  void reportsTheBudgetPeriodOfTheDecision() {
    PolicyDecision decision = engine.evaluate(context().build());

    assertThat(decision.budgetPeriod())
        .isEqualTo(
            new BudgetPeriod(
                Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z")));
  }

  @Test
  void deniesASuspendedAgent() {
    PolicyDecision decision = engine.evaluate(context().status(AgentStatus.SUSPENDED).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.AGENT_SUSPENDED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"EUR", "GBP", "XYZ", "", "  "})
  void deniesAnUnsupportedCurrency(String currency) {
    PolicyDecision decision = engine.evaluate(context().currency(currency).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.CURRENCY_NOT_SUPPORTED);
  }

  @Test
  void acceptsUsdRegardlessOfCaseAndPadding() {
    PolicyDecision decision = engine.evaluate(context().currency("  usd  ").build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.APPROVE);
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
  void deniesANonPositiveAmount(long amountMinor) {
    PolicyDecision decision = engine.evaluate(context().amountMinor(amountMinor).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.INVALID_AMOUNT);
  }

  @Test
  void acceptsTheHardCapExactly() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .amountMinor(HARD_CAP)
                .weeklyLimitMinor(HARD_CAP)
                .autoApproveThresholdMinor(HARD_CAP)
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.APPROVE);
  }

  @Test
  void deniesOneMinorUnitAboveTheHardCap() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .amountMinor(HARD_CAP + 1)
                .weeklyLimitMinor(HARD_CAP * 2)
                .autoApproveThresholdMinor(HARD_CAP)
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.AMOUNT_EXCEEDS_CAP);
  }

  @Test
  void deniesAPayeeOutsideTheAllowList() {
    PolicyDecision decision = engine.evaluate(context().payeeEmail("mallory@example.com").build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.PAYEE_NOT_ALLOWED);
  }

  @Test
  void matchesPayeesIgnoringCaseAndOuterWhitespace() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .allowedPayees(Set.of(" Alice@Example.COM "))
                .payeeEmail("  alice@EXAMPLE.com  ")
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.APPROVE);
  }

  @Test
  void deniesWhenThePayeeIsNull() {
    PolicyDecision decision = engine.evaluate(context().payeeEmail(null).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.PAYEE_NOT_ALLOWED);
  }

  @Test
  void allowsSpendingExactlyUpToTheWeeklyLimit() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .spentMinor(40_000L)
                .reservedMinor(5_000L)
                .amountMinor(5_000L)
                .autoApproveThresholdMinor(5_000L)
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.APPROVE);
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  void deniesOneMinorUnitAboveTheWeeklyLimit() {
    PolicyDecision decision =
        engine.evaluate(
            context().spentMinor(40_000L).reservedMinor(5_000L).amountMinor(5_001L).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.BUDGET_EXCEEDED);
  }

  @Test
  void pendingApprovalsReserveBudget() {
    PolicyDecision decision =
        engine.evaluate(
            context().spentMinor(1_200L).reservedMinor(48_800L).amountMinor(1L).build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.BUDGET_EXCEEDED);
  }

  @Test
  void treatsAnOverflowingCommittedBudgetAsExceeded() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .weeklyLimitMinor(Long.MAX_VALUE)
                .autoApproveThresholdMinor(Long.MAX_VALUE)
                .spentMinor(Long.MAX_VALUE)
                .reservedMinor(1L)
                .amountMinor(1L)
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision)).containsExactly(PolicyReasonCode.BUDGET_EXCEEDED);
  }

  @Test
  void collectsAllHardRuleViolationsInTheFixedOrder() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .status(AgentStatus.SUSPENDED)
                .currency("EUR")
                .amountMinor(150_000L)
                .payeeEmail("mallory@example.com")
                .spentMinor(900_000L)
                .weeklyLimitMinor(1_000_000L)
                .build());

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(codes(decision))
        .containsExactly(
            PolicyReasonCode.AGENT_SUSPENDED,
            PolicyReasonCode.CURRENCY_NOT_SUPPORTED,
            PolicyReasonCode.AMOUNT_EXCEEDS_CAP,
            PolicyReasonCode.PAYEE_NOT_ALLOWED,
            PolicyReasonCode.BUDGET_EXCEEDED);
  }

  @Test
  void placesInvalidAmountAfterAgentAndCurrencyRules() {
    PolicyDecision decision =
        engine.evaluate(
            context()
                .status(AgentStatus.SUSPENDED)
                .currency("EUR")
                .amountMinor(0L)
                .payeeEmail("mallory@example.com")
                .build());

    assertThat(codes(decision))
        .containsExactly(
            PolicyReasonCode.AGENT_SUSPENDED,
            PolicyReasonCode.CURRENCY_NOT_SUPPORTED,
            PolicyReasonCode.INVALID_AMOUNT,
            PolicyReasonCode.PAYEE_NOT_ALLOWED);
  }

  @Test
  void doesNotApplyTheBudgetRuleToANonPositiveAmount() {
    PolicyDecision decision =
        engine.evaluate(context().amountMinor(-1L).spentMinor(50_000L).build());

    assertThat(codes(decision)).containsExactly(PolicyReasonCode.INVALID_AMOUNT);
  }

  @Test
  void decisionReasonsAreImmutable() {
    PolicyDecision decision = engine.evaluate(context().status(AgentStatus.SUSPENDED).build());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> decision.reasons().add(PolicyReason.of(PolicyReasonCode.INVALID_AMOUNT)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void denyDecisionsAlwaysCarryAtLeastOneReason() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PolicyDecision(PolicyOutcome.DENY, List.of(), period()))
        .withMessageContaining("DENY");
  }

  @Test
  void nonDenyDecisionsCannotCarryReasons() {
    assertThatIllegalArgumentException()
        .isThrownBy(() ->
            new PolicyDecision(
                PolicyOutcome.APPROVE,
                List.of(PolicyReason.of(PolicyReasonCode.INVALID_AMOUNT)),
                period()))
        .withMessageContaining("reasons");
  }

  @Test
  void rejectsANullContext() {
    assertThatNullPointerException().isThrownBy(() -> engine.evaluate(null));
  }

  @Test
  void rejectsANonPositiveHardCap() {
    assertThatIllegalArgumentException().isThrownBy(() -> new PolicyEngine(0L));
    assertThatIllegalArgumentException().isThrownBy(() -> new PolicyEngine(-1L));
  }

  @Test
  void contextRejectsNegativeSpentOrReservedBudget() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> context().spentMinor(-1L).build())
        .withMessageContaining("spentMinor");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> context().reservedMinor(-1L).build())
        .withMessageContaining("reservedMinor");
  }

  @Test
  void policyRejectsNegativeLimits() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AgentPolicy(-1L, 0L, Set.of("alice@example.com")))
        .withMessageContaining("weeklyLimitMinor");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AgentPolicy(1_000L, -1L, Set.of("alice@example.com")))
        .withMessageContaining("autoApproveThresholdMinor");
  }

  @Test
  void policyNormalizesAllowedPayees() {
    AgentPolicy policy =
        new AgentPolicy(
            50_000L,
            10_000L,
            Set.of("  Alice@Example.COM ", "bob@example.com", "ALICE@example.com"));

    assertThat(policy.allowedPayees())
        .containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
  }

  @Test
  void policyRejectsNullPayeeEntries() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AgentPolicy(50_000L, 10_000L, java.util.Collections.singleton(null)))
        .withMessageContaining("payee");
  }

  @Test
  void reasonDefaultsToTheCodeMessage() {
    PolicyReason reason = PolicyReason.of(PolicyReasonCode.PAYEE_NOT_ALLOWED);

    assertThat(reason.code()).isEqualTo(PolicyReasonCode.PAYEE_NOT_ALLOWED);
    assertThat(reason.message()).isEqualTo(PolicyReasonCode.PAYEE_NOT_ALLOWED.message());
  }

  private static List<PolicyReasonCode> codes(PolicyDecision decision) {
    return decision.reasons().stream().map(PolicyReason::code).toList();
  }

  private static BudgetPeriod period() {
    return new BudgetPeriod(
        Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-16T00:00:00Z"));
  }

  private static ContextBuilder context() {
    return new ContextBuilder();
  }

  private static final class ContextBuilder {
    private AgentStatus status = AgentStatus.ACTIVE;
    private long amountMinor = 1_200L;
    private String currency = "USD";
    private String payeeEmail = "alice@example.com";
    private long spentMinor;
    private long reservedMinor;
    private long weeklyLimitMinor = 50_000L;
    private long autoApproveThresholdMinor = 10_000L;
    private Set<String> allowedPayees = Set.of("alice@example.com");
    private Clock clock = CLOCK;

    ContextBuilder status(AgentStatus status) {
      this.status = status;
      return this;
    }

    ContextBuilder amountMinor(long amountMinor) {
      this.amountMinor = amountMinor;
      return this;
    }

    ContextBuilder currency(String currency) {
      this.currency = currency;
      return this;
    }

    ContextBuilder payeeEmail(String payeeEmail) {
      this.payeeEmail = payeeEmail;
      return this;
    }

    ContextBuilder spentMinor(long spentMinor) {
      this.spentMinor = spentMinor;
      return this;
    }

    ContextBuilder reservedMinor(long reservedMinor) {
      this.reservedMinor = reservedMinor;
      return this;
    }

    ContextBuilder weeklyLimitMinor(long weeklyLimitMinor) {
      this.weeklyLimitMinor = weeklyLimitMinor;
      return this;
    }

    ContextBuilder autoApproveThresholdMinor(long autoApproveThresholdMinor) {
      this.autoApproveThresholdMinor = autoApproveThresholdMinor;
      return this;
    }

    ContextBuilder allowedPayees(Set<String> allowedPayees) {
      this.allowedPayees = allowedPayees;
      return this;
    }

    PolicyContext build() {
      return new PolicyContext(
          AGENT_ID,
          status,
          new AgentPolicy(weeklyLimitMinor, autoApproveThresholdMinor, allowedPayees),
          amountMinor,
          currency,
          payeeEmail,
          spentMinor,
          reservedMinor,
          clock);
    }
  }
}
