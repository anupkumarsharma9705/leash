package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentStateMachineTest {

  private static final Map<PaymentState, Set<PaymentState>> SPEC = specTable();

  private final PaymentStateMachine machine = new PaymentStateMachine();

  @ParameterizedTest(name = "{0} -> {1} allowed={2}")
  @MethodSource("allTransitions")
  void appliesExactlyTheDocumentedTransitionTable(
      PaymentState from, PaymentState to, boolean allowed) {
    if (allowed) {
      assertThat(machine.canTransition(from, to)).isTrue();
      assertThat(machine.transition(from, to)).isEqualTo(to);
    } else {
      assertThat(machine.canTransition(from, to)).isFalse();
      assertThatThrownBy(() -> machine.transition(from, to))
          .isInstanceOfSatisfying(
              IllegalStateTransitionException.class,
              exception -> {
                assertThat(exception.from()).isEqualTo(from);
                assertThat(exception.to()).isEqualTo(to);
                assertThat(exception)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
              });
    }
  }

  @Test
  void terminalStatesNeverMove() {
    for (PaymentState terminal :
        Set.of(
            PaymentState.SUCCEEDED,
            PaymentState.FAILED,
            PaymentState.REJECTED,
            PaymentState.DENIED)) {
      assertThat(machine.isTerminal(terminal)).isTrue();
      for (PaymentState target : PaymentState.values()) {
        assertThat(machine.canTransition(terminal, target)).isFalse();
        assertThatThrownBy(() -> machine.transition(terminal, target))
            .isInstanceOf(IllegalStateTransitionException.class);
      }
    }
  }

  @Test
  void nonTerminalStatesAreNotTerminal() {
    assertThat(machine.isTerminal(PaymentState.PENDING_APPROVAL)).isFalse();
    assertThat(machine.isTerminal(PaymentState.APPROVED)).isFalse();
    assertThat(machine.isTerminal(PaymentState.SUBMITTED)).isFalse();
  }

  @Test
  void exposesTheAllowedTargetsForEachState() {
    assertThat(machine.allowedTargets(PaymentState.PENDING_APPROVAL))
        .containsExactlyInAnyOrder(PaymentState.APPROVED, PaymentState.REJECTED);
    assertThat(machine.allowedTargets(PaymentState.APPROVED))
        .containsExactlyInAnyOrder(PaymentState.SUBMITTED, PaymentState.FAILED);
    assertThat(machine.allowedTargets(PaymentState.SUBMITTED))
        .containsExactlyInAnyOrder(PaymentState.SUCCEEDED, PaymentState.FAILED);
    assertThat(machine.allowedTargets(PaymentState.SUCCEEDED)).isEmpty();
    assertThat(machine.allowedTargets(PaymentState.FAILED)).isEmpty();
    assertThat(machine.allowedTargets(PaymentState.REJECTED)).isEmpty();
    assertThat(machine.allowedTargets(PaymentState.DENIED)).isEmpty();
  }

  @Test
  void allowedTargetsAreImmutable() {
    assertThatThrownBy(
            () -> machine.allowedTargets(PaymentState.APPROVED).add(PaymentState.SUCCEEDED))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void successCannotBeReachedBeforeSubmission() {
    assertThat(machine.canTransition(PaymentState.SUBMITTED, PaymentState.SUCCEEDED)).isTrue();
    assertThat(machine.canTransition(PaymentState.SUBMITTED, PaymentState.FAILED)).isTrue();
    assertThat(machine.canTransition(PaymentState.PENDING_APPROVAL, PaymentState.SUCCEEDED))
        .isFalse();
    assertThat(machine.canTransition(PaymentState.APPROVED, PaymentState.SUCCEEDED)).isFalse();
    assertThat(machine.canTransition(PaymentState.PENDING_APPROVAL, PaymentState.FAILED)).isFalse();
    // APPROVED -> FAILED is the submission-failure path; webhook handling gates on SUBMITTED.
    assertThat(machine.canTransition(PaymentState.APPROVED, PaymentState.FAILED)).isTrue();
  }

  @Test
  void rejectsNullStateArguments() {
    assertThatNullPointerException()
        .isThrownBy(() -> machine.transition(null, PaymentState.APPROVED));
    assertThatNullPointerException()
        .isThrownBy(() -> machine.transition(PaymentState.APPROVED, null));
    assertThatNullPointerException()
        .isThrownBy(() -> machine.canTransition(null, PaymentState.APPROVED));
    assertThatNullPointerException()
        .isThrownBy(() -> machine.canTransition(PaymentState.APPROVED, null));
    assertThatNullPointerException().isThrownBy(() -> machine.allowedTargets(null));
    assertThatNullPointerException().isThrownBy(() -> machine.isTerminal(null));
  }

  private static List<Arguments> allTransitions() {
    List<Arguments> rows =
        new ArrayList<>(PaymentState.values().length * PaymentState.values().length);
    for (PaymentState from : PaymentState.values()) {
      for (PaymentState to : PaymentState.values()) {
        rows.add(Arguments.of(from, to, SPEC.get(from).contains(to)));
      }
    }
    return rows;
  }

  private static Map<PaymentState, Set<PaymentState>> specTable() {
    Map<PaymentState, Set<PaymentState>> table = new EnumMap<>(PaymentState.class);
    table.put(
        PaymentState.PENDING_APPROVAL, EnumSet.of(PaymentState.APPROVED, PaymentState.REJECTED));
    table.put(PaymentState.APPROVED, EnumSet.of(PaymentState.SUBMITTED, PaymentState.FAILED));
    table.put(PaymentState.SUBMITTED, EnumSet.of(PaymentState.SUCCEEDED, PaymentState.FAILED));
    table.put(PaymentState.SUCCEEDED, EnumSet.noneOf(PaymentState.class));
    table.put(PaymentState.FAILED, EnumSet.noneOf(PaymentState.class));
    table.put(PaymentState.REJECTED, EnumSet.noneOf(PaymentState.class));
    table.put(PaymentState.DENIED, EnumSet.noneOf(PaymentState.class));
    return table;
  }
}
