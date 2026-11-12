package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import com.leash.policy.PolicyReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRecordTest {

  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void acceptsAnApprovedRecordWithNoReasons() {
    PaymentRecord record = record(PaymentState.SUBMITTED, PolicyOutcome.APPROVE, List.of());

    assertThat(record.decisionReasons()).isEmpty();
    assertThat(record.amountMinor()).isEqualTo(1200);
  }

  @Test
  void acceptsADeniedRecordWithReasons() {
    PaymentRecord record =
        record(
            PaymentState.DENIED,
            PolicyOutcome.DENY,
            List.of(PolicyReason.of(PolicyReasonCode.PAYEE_NOT_ALLOWED)));

    assertThat(record.decisionReasons()).hasSize(1);
  }

  @Test
  void requiresAReasonForDeny() {
    assertThatThrownBy(() -> record(PaymentState.DENIED, PolicyOutcome.DENY, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DENY requires");
  }

  @Test
  void rejectsReasonsOnNonDenyOutcomes() {
    assertThatThrownBy(() ->
            record(
                PaymentState.PENDING_APPROVAL,
                PolicyOutcome.ESCALATE,
                List.of(PolicyReason.of(PolicyReasonCode.BUDGET_EXCEEDED))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot carry reasons");
  }

  @Test
  void denyMustBePersistedAsDenied() {
    assertThatThrownBy(() ->
            record(
                PaymentState.APPROVED,
                PolicyOutcome.DENY,
                List.of(PolicyReason.of(PolicyReasonCode.PAYEE_NOT_ALLOWED))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DENY must be persisted as DENIED");
  }

  @Test
  void reasonsAreDefensivelyCopied() {
    List<PolicyReason> reasons =
        new java.util.ArrayList<>(List.of(PolicyReason.of(PolicyReasonCode.PAYEE_NOT_ALLOWED)));
    PaymentRecord record = record(PaymentState.DENIED, PolicyOutcome.DENY, reasons);

    reasons.clear();

    assertThat(record.decisionReasons()).hasSize(1);
    assertThatThrownBy(
            () -> record.decisionReasons().add(reasons.isEmpty() ? null : reasons.getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static PaymentRecord record(
      PaymentState state, PolicyOutcome outcome, List<PolicyReason> reasons) {
    return new PaymentRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "invoice-1",
        "a".repeat(64),
        "alice@example.com",
        1200,
        "USD",
        "Invoice inv-1",
        state,
        outcome,
        reasons,
        null,
        null,
        new BudgetSnapshot(50_000, 1200, 0),
        0,
        NOW,
        NOW);
  }
}
