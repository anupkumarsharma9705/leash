package com.leash.payment.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The only place payment states change.
 *
 * <p>Pure Java: no Spring, JPA, or I/O. Every allowed and forbidden transition is explicit. A
 * terminal state never moves, and callers that replay webhooks or reconciler retries must check
 * {@link #canTransition(PaymentState, PaymentState)} before asking for a transition.
 *
 * <p>Webhook handlers must additionally require the current state to be {@code SUBMITTED}: the
 * table allows {@code APPROVED -> FAILED} for a failed submission, which is not a webhook path.
 */
public final class PaymentStateMachine {

  private static final Map<PaymentState, Set<PaymentState>> ALLOWED_TRANSITIONS =
      Map.of(
          PaymentState.PENDING_APPROVAL, Set.of(PaymentState.APPROVED, PaymentState.REJECTED),
          PaymentState.APPROVED, Set.of(PaymentState.SUBMITTED, PaymentState.FAILED),
          PaymentState.SUBMITTED, Set.of(PaymentState.SUCCEEDED, PaymentState.FAILED),
          PaymentState.SUCCEEDED, Set.of(),
          PaymentState.FAILED, Set.of(),
          PaymentState.REJECTED, Set.of(),
          PaymentState.DENIED, Set.of());

  private static final Set<PaymentState> TERMINAL_STATES =
      Set.of(
          PaymentState.SUCCEEDED, PaymentState.FAILED, PaymentState.REJECTED, PaymentState.DENIED);

  /**
   * Returns the target state when the transition is allowed.
   *
   * @throws IllegalStateTransitionException when the transition is not in the table
   */
  public PaymentState transition(PaymentState from, PaymentState to) {
    Objects.requireNonNull(from, "from is required");
    Objects.requireNonNull(to, "to is required");
    if (!canTransition(from, to)) {
      throw new IllegalStateTransitionException(from, to);
    }
    return to;
  }

  public boolean canTransition(PaymentState from, PaymentState to) {
    Objects.requireNonNull(from, "from is required");
    Objects.requireNonNull(to, "to is required");
    return ALLOWED_TRANSITIONS.get(from).contains(to);
  }

  public boolean isTerminal(PaymentState state) {
    Objects.requireNonNull(state, "state is required");
    return TERMINAL_STATES.contains(state);
  }

  /** The immutable set of states reachable from {@code from} in one transition. */
  public Set<PaymentState> allowedTargets(PaymentState from) {
    Objects.requireNonNull(from, "from is required");
    return ALLOWED_TRANSITIONS.get(from);
  }
}
