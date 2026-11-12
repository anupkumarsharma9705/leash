package com.leash.payment.app;

import com.leash.agent.app.AgentService;
import com.leash.agent.domain.Agent;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.payment.domain.ApprovalAlreadyDecidedException;
import com.leash.payment.domain.ApprovalNotFoundException;
import com.leash.payment.domain.ApprovalRecord;
import com.leash.payment.domain.ApprovalStatus;
import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.BudgetTotals;
import com.leash.payment.domain.IdempotencyKeyReusedException;
import com.leash.payment.domain.IdempotencyKeys;
import com.leash.payment.domain.PaymentCommand;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PaymentStateMachine;
import com.leash.payment.domain.PayoutItemWebhook;
import com.leash.paypal.PayPalPayoutStatus;
import com.leash.paypal.PayPalStatusMapper;
import com.leash.paypal.PayoutSubmission;
import com.leash.policy.BudgetPeriod;
import com.leash.policy.PolicyContext;
import com.leash.policy.PolicyDecision;
import com.leash.policy.PolicyEngine;
import com.leash.policy.PolicyOutcome;
import com.leash.policy.PolicyReason;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, network-free transactions of the payment gateway.
 *
 * <p>{@link #prepare} locks the agent row, checks idempotency, computes budget aggregates inside
 * the lock, runs the pure policy engine, and persists the request with its ledger facts before the
 * caller commits. PayPal is never called here.
 */
@Service
public class PaymentTransactionService {

  private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);

  private final AgentService agentService;
  private final PaymentStore paymentStore;
  private final LedgerService ledgerService;
  private final PolicyEngine policyEngine;
  private final PaymentStateMachine stateMachine;
  private final Clock clock;

  public PaymentTransactionService(
      AgentService agentService,
      PaymentStore paymentStore,
      LedgerService ledgerService,
      PolicyEngine policyEngine,
      PaymentStateMachine stateMachine,
      Clock clock) {
    this.agentService = agentService;
    this.paymentStore = paymentStore;
    this.ledgerService = ledgerService;
    this.policyEngine = policyEngine;
    this.stateMachine = stateMachine;
    this.clock = clock;
  }

  /**
   * TX-A: decide and persist one payment request.
   *
   * <p>The agent row is locked for the whole transaction, so two requests for the same agent cannot
   * read the same budget aggregates concurrently.
   */
  @Transactional
  public PreparedPayment prepare(
      UUID authenticatedAgentId, PaymentCommand command, String idempotencyKey) {
    Objects.requireNonNull(authenticatedAgentId, "authenticatedAgentId is required");
    Objects.requireNonNull(command, "command is required");
    String key = IdempotencyKeys.validate(idempotencyKey);

    Agent agent = agentService.lockAgent(authenticatedAgentId);
    String fingerprint = command.fingerprint();

    Optional<PaymentRecord> existing =
        paymentStore.findByAgentAndIdempotencyKey(authenticatedAgentId, key);
    if (existing.isPresent()) {
      PaymentRecord replay = existing.get();
      if (!replay.requestFingerprint().equals(fingerprint)) {
        throw new IdempotencyKeyReusedException();
      }
      log.info(
          "Replayed idempotent payment: paymentId={} agentId={}",
          replay.id(),
          authenticatedAgentId);
      return new PreparedPayment(replay, true);
    }

    BudgetPeriod period = BudgetPeriod.current(clock);
    BudgetTotals totals =
        paymentStore.budgetTotals(
            authenticatedAgentId, period.startInclusive(), period.endExclusive());

    PolicyContext context =
        new PolicyContext(
            agent.id(),
            agent.status(),
            agent.policy(),
            command.amountMinor(),
            command.currency(),
            command.payeeEmail(),
            totals.spentMinor(),
            totals.reservedMinor(),
            clock);
    PolicyDecision decision = policyEngine.evaluate(context);

    PaymentState state = initialState(decision.outcome());
    BudgetTotals afterDecision =
        applyReservation(decision.outcome(), totals, command.amountMinor());
    BudgetSnapshot budget =
        new BudgetSnapshot(
            agent.policy().weeklyLimitMinor(),
            afterDecision.spentMinor(),
            afterDecision.reservedMinor());

    Instant now = clock.instant();
    PaymentRecord record =
        new PaymentRecord(
            UUID.randomUUID(),
            authenticatedAgentId,
            key,
            fingerprint,
            command.payeeEmail(),
            command.amountMinor(),
            command.currency(),
            command.memo(),
            state,
            decision.outcome(),
            decision.reasons(),
            null,
            null,
            budget,
            0,
            now,
            now);
    paymentStore.insert(record);

    ledgerService.append(
        authenticatedAgentId,
        record.id(),
        LedgerEventType.PAYMENT_REQUESTED,
        "agent",
        Map.of(
            "payeeEmail", command.payeeEmail(),
            "amountMinor", command.amountMinor(),
            "currency", command.currency(),
            "idempotencyKey", key));
    ledgerService.append(
        authenticatedAgentId,
        record.id(),
        LedgerEventType.DECISION_MADE,
        "policy-engine",
        decisionPayload(decision, budget));

    if (state == PaymentState.PENDING_APPROVAL) {
      UUID approvalId = UUID.randomUUID();
      paymentStore.insertApproval(approvalId, record.id(), now);
      ledgerService.append(
          authenticatedAgentId,
          record.id(),
          LedgerEventType.APPROVAL_CREATED,
          "policy-engine",
          Map.of("approvalId", approvalId.toString()));
    }

    log.info(
        "Recorded payment decision: paymentId={} agentId={} outcome={} state={}",
        record.id(),
        authenticatedAgentId,
        decision.outcome(),
        state);
    return new PreparedPayment(record, false);
  }

  /**
   * Decides one pending approval.
   *
   * <p>The agent row is locked first, so a policy edit and an approval decision serialize exactly
   * like a payment decision. The approval is re-read after the lock: a racing decision that won
   * while this caller waited is either the same decision (a no-op) or the opposite one (a
   * conflict).
   *
   * <p>Approve re-runs the full policy engine with this payment's own reservation released. A
   * {@code DENY} auto-rejects the approval and records the policy reasons in the ledger; otherwise
   * both the approval and the payment move to {@code APPROVED} in the same transaction. The caller
   * submits to PayPal only after this transaction commits.
   *
   * @param approve {@code true} to approve, {@code false} to reject
   * @param actor the authenticated admin; recorded as {@code decided_by}
   * @param note optional human note, at most 500 characters
   * @throws ApprovalNotFoundException when the approval does not exist
   * @throws ApprovalAlreadyDecidedException when the opposite decision already committed
   */
  @Transactional
  public ApprovalDecision decideApproval(
      UUID approvalId, boolean approve, String actor, String note) {
    Objects.requireNonNull(approvalId, "approvalId is required");
    String decidedBy = requireActor(actor);
    String normalizedNote = normalizeNote(note);

    ApprovalRecord known =
        paymentStore
            .findApproval(approvalId)
            .orElseThrow(() -> new ApprovalNotFoundException(approvalId));
    PaymentRecord initial = requirePayment(known.paymentId());

    // Serialize with policy edits and other decisions for this agent before re-reading.
    Agent agent = agentService.lockAgent(initial.agentId());

    ApprovalRecord approval =
        paymentStore
            .findApproval(approvalId)
            .orElseThrow(() -> new ApprovalNotFoundException(approvalId));
    PaymentRecord payment = requirePayment(approval.paymentId());

    if (approval.status() != ApprovalStatus.PENDING) {
      return replayOrConflict(approval, payment, approve, approvalId);
    }
    if (payment.state() != PaymentState.PENDING_APPROVAL) {
      // A pending approval whose payment already left PENDING_APPROVAL is inconsistent; fail safe.
      throw new ApprovalAlreadyDecidedException(approvalId);
    }

    Instant now = clock.instant();
    if (!approve) {
      return decide(
          approval,
          payment,
          ApprovalStatus.REJECTED,
          now,
          decidedBy,
          normalizedNote,
          "HUMAN_REJECTION",
          List.of());
    }

    PolicyDecision recheck = recheckPolicy(agent, payment);
    if (recheck.outcome() == PolicyOutcome.DENY) {
      return decide(
          approval,
          payment,
          ApprovalStatus.REJECTED,
          now,
          decidedBy,
          normalizedNote,
          "HARD_RULE_VIOLATION",
          recheck.reasons());
    }
    return decide(
        approval,
        payment,
        ApprovalStatus.APPROVED,
        now,
        decidedBy,
        normalizedNote,
        "HUMAN_APPROVAL",
        List.of());
  }

  private ApprovalDecision decide(
      ApprovalRecord approval,
      PaymentRecord payment,
      ApprovalStatus decision,
      Instant now,
      String decidedBy,
      String note,
      String reason,
      List<PolicyReason> policyReasons) {
    Optional<ApprovalRecord> won =
        paymentStore.decideApproval(
            approval.id(), approval.version(), decision, decidedBy, note, now);
    if (won.isEmpty()) {
      ApprovalRecord winner =
          paymentStore
              .findApproval(approval.id())
              .orElseThrow(() -> new ApprovalNotFoundException(approval.id()));
      return replayOrConflict(
          winner,
          requirePayment(winner.paymentId()),
          decision == ApprovalStatus.APPROVED,
          approval.id());
    }

    PaymentState next =
        decision == ApprovalStatus.APPROVED ? PaymentState.APPROVED : PaymentState.REJECTED;
    PaymentRecord updated =
        paymentStore.updateState(
            payment.id(), payment.version(), stateMachine.transition(payment.state(), next), now);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approval.id().toString());
    payload.put("decision", decision.name());
    payload.put("reason", reason);
    payload.put("previousState", PaymentState.PENDING_APPROVAL.name());
    payload.put("state", updated.state().name());
    if (note != null) {
      payload.put("note", note);
    }
    if (!policyReasons.isEmpty()) {
      payload.put("policyReasons", reasonMaps(policyReasons));
    }
    ledgerService.append(
        payment.agentId(), payment.id(), LedgerEventType.APPROVAL_DECIDED, decidedBy, payload);

    log.info(
        "Decided approval: approvalId={} paymentId={} decision={} reason={} actor={}",
        approval.id(),
        payment.id(),
        decision,
        reason,
        decidedBy);
    return new ApprovalDecision(won.get(), updated, false, policyReasons);
  }

  private PolicyDecision recheckPolicy(Agent agent, PaymentRecord payment) {
    BudgetPeriod period = BudgetPeriod.current(clock);
    BudgetTotals totals =
        paymentStore.budgetTotals(agent.id(), period.startInclusive(), period.endExclusive());
    boolean reservationInThisPeriod =
        !payment.createdAt().isBefore(period.startInclusive())
            && payment.createdAt().isBefore(period.endExclusive());
    BudgetTotals available =
        reservationInThisPeriod ? totals.minusReserved(payment.amountMinor()) : totals;
    PolicyContext context =
        new PolicyContext(
            agent.id(),
            agent.status(),
            agent.policy(),
            payment.amountMinor(),
            payment.currency(),
            payment.payeeEmail(),
            available.spentMinor(),
            available.reservedMinor(),
            clock);
    return policyEngine.evaluate(context);
  }

  private static ApprovalDecision replayOrConflict(
      ApprovalRecord current, PaymentRecord payment, boolean approveRequested, UUID approvalId) {
    ApprovalStatus requested = approveRequested ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
    if (current.status() == requested) {
      return new ApprovalDecision(current, payment, true, List.of());
    }
    throw new ApprovalAlreadyDecidedException(approvalId);
  }

  private static List<Map<String, String>> reasonMaps(List<PolicyReason> reasons) {
    return reasons.stream()
        .map(reason -> Map.of("code", reason.code().name(), "message", reason.message()))
        .toList();
  }

  private static String requireActor(String actor) {
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("actor is required");
    }
    String trimmed = actor.trim();
    return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
  }

  private static String normalizeNote(String note) {
    if (note == null) {
      return null;
    }
    String trimmed = note.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > 500) {
      throw new IllegalArgumentException("note must be at most 500 characters");
    }
    return trimmed;
  }

  /** TX-B: records a successful PayPal submission. */
  @Transactional
  public PaymentRecord markSubmitted(UUID paymentId, PayoutSubmission submission) {
    return markSubmitted(paymentId, submission, false);
  }

  /** TX-B for the reconciler: records a successful retry and the repair event. */
  @Transactional
  public PaymentRecord markSubmittedByReconciler(UUID paymentId, PayoutSubmission submission) {
    return markSubmitted(paymentId, submission, true);
  }

  private PaymentRecord markSubmitted(
      UUID paymentId, PayoutSubmission submission, boolean reconciled) {
    Objects.requireNonNull(submission, "submission is required");
    PaymentRecord current = requirePayment(paymentId);

    if (current.state() == PaymentState.SUBMITTED
        || current.state() == PaymentState.SUCCEEDED
        || current.state() == PaymentState.FAILED) {
      return current;
    }
    if (current.state() != PaymentState.APPROVED) {
      log.warn(
          "Ignoring a PayPal submission for a payment that is not APPROVED: paymentId={} state={}",
          paymentId,
          current.state());
      return current;
    }

    PaymentState next = stateMachine.transition(current.state(), PaymentState.SUBMITTED);
    PaymentRecord updated =
        paymentStore.updatePayPalState(
            paymentId,
            current.version(),
            next,
            submission.payoutBatchId(),
            submission.rawBatchStatus(),
            clock.instant());
    ledgerService.append(
        current.agentId(),
        paymentId,
        LedgerEventType.PAYPAL_SUBMITTED,
        "paypal-adapter",
        paypalPayload(
            submission.payoutBatchId(), Objects.toString(submission.rawBatchStatus(), "")));
    if (reconciled) {
      ledgerService.append(
          current.agentId(),
          paymentId,
          LedgerEventType.RECONCILED,
          "reconciler",
          reconciledPayload(
              "RETRY_SUBMIT",
              PaymentState.APPROVED,
              next,
              submission.payoutBatchId(),
              submission.rawBatchStatus(),
              null,
              null));
    }
    return updated;
  }

  /** TX-B: records a definitive PayPal rejection, releasing the budget. */
  @Transactional
  public PaymentRecord markSubmissionFailed(UUID paymentId, Integer httpStatus, String debugId) {
    return markSubmissionFailed(paymentId, httpStatus, debugId, false);
  }

  /** TX-B for the reconciler: records a definitive retry rejection and the repair event. */
  @Transactional
  public PaymentRecord markSubmissionFailedByReconciler(
      UUID paymentId, Integer httpStatus, String debugId) {
    return markSubmissionFailed(paymentId, httpStatus, debugId, true);
  }

  private PaymentRecord markSubmissionFailed(
      UUID paymentId, Integer httpStatus, String debugId, boolean reconciled) {
    PaymentRecord current = requirePayment(paymentId);

    if (current.state() == PaymentState.FAILED) {
      return current;
    }
    if (current.state() == PaymentState.SUBMITTED || current.state() == PaymentState.SUCCEEDED) {
      log.warn(
          "Ignoring a late submission failure for an accepted payment: paymentId={} state={}",
          paymentId,
          current.state());
      return current;
    }
    if (current.state() != PaymentState.APPROVED) {
      log.warn(
          "Ignoring a submission failure for a payment that is not APPROVED: paymentId={} state={}",
          paymentId,
          current.state());
      return current;
    }

    PaymentState next = stateMachine.transition(current.state(), PaymentState.FAILED);
    PaymentRecord updated =
        paymentStore.updatePayPalState(
            paymentId,
            current.version(),
            next,
            current.paypalBatchId(),
            "SUBMISSION_FAILED",
            clock.instant());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("state", "FAILED");
    payload.put("reason", "SUBMISSION_FAILED");
    if (httpStatus != null) {
      payload.put("httpStatus", httpStatus);
    }
    if (debugId != null && !debugId.isBlank()) {
      payload.put("debugId", debugId);
    }
    ledgerService.append(
        current.agentId(),
        paymentId,
        LedgerEventType.PAYPAL_STATUS_CHANGED,
        reconciled ? "reconciler" : "paypal-adapter",
        payload);
    if (reconciled) {
      ledgerService.append(
          current.agentId(),
          paymentId,
          LedgerEventType.RECONCILED,
          "reconciler",
          reconciledPayload(
              "RETRY_SUBMIT_FAILED",
              PaymentState.APPROVED,
              next,
              current.paypalBatchId(),
              "SUBMISSION_FAILED",
              null,
              httpStatus));
    }
    return updated;
  }

  /**
   * Applies one verified PayPal Payouts item webhook to a {@code SUBMITTED} payment.
   *
   * <p>The item status is the source of truth. Only {@code SUBMITTED} can move, so a late or
   * out-of-order webhook can never regress a terminal state. A webhook whose item stays pending
   * refreshes the raw status and {@code updated_at} just like a reconciler poll. An {@code
   * APPROVED} payment is treated as a retryable race with TX-B: the exception leaves the webhook
   * unprocessed so PayPal can redeliver it after submission completes.
   */
  @Transactional
  public void applyPayoutItemWebhook(PayoutItemWebhook webhook) {
    Objects.requireNonNull(webhook, "webhook is required");
    PaymentRecord current = paymentStore.findById(webhook.paymentId()).orElse(null);
    if (current == null) {
      log.warn(
          "Ignoring Payouts webhook for an unknown payment: paymentId={} eventType={}",
          webhook.paymentId(),
          webhook.eventType());
      return;
    }

    if (current.state() == PaymentState.APPROVED) {
      throw new IllegalStateException(
          "Payment is not SUBMITTED yet; retry the webhook: " + webhook.paymentId());
    }
    if (current.state() != PaymentState.SUBMITTED) {
      log.info(
          "Ignoring Payouts webhook for a payment that is not open:"
              + " paymentId={} state={} eventType={}",
          webhook.paymentId(),
          current.state(),
          webhook.eventType());
      return;
    }

    PaymentState mapped = PayPalStatusMapper.resolve(null, webhook.rawItemStatus());
    PaymentState next =
        mapped == PaymentState.SUBMITTED
                || stateMachine.canTransition(PaymentState.SUBMITTED, mapped)
            ? mapped
            : PaymentState.SUBMITTED;
    String payoutBatchId = firstPresent(current.paypalBatchId(), webhook.payoutBatchId());

    paymentStore.updatePayPalState(
        webhook.paymentId(),
        current.version(),
        next,
        payoutBatchId,
        webhook.rawItemStatus(),
        clock.instant());
    ledgerService.append(
        current.agentId(),
        webhook.paymentId(),
        LedgerEventType.PAYPAL_STATUS_CHANGED,
        "paypal-webhook",
        webhookPayload(
            PaymentState.SUBMITTED,
            next,
            payoutBatchId,
            webhook.rawItemStatus(),
            webhook.eventType()));
  }

  private static Map<String, Object> webhookPayload(
      PaymentState previousState,
      PaymentState state,
      String payoutBatchId,
      String rawItemStatus,
      String eventType) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousState", previousState.name());
    payload.put("state", state.name());
    if (payoutBatchId != null && !payoutBatchId.isBlank()) {
      payload.put("payoutBatchId", payoutBatchId);
    }
    if (rawItemStatus != null && !rawItemStatus.isBlank()) {
      payload.put("rawItemStatus", rawItemStatus);
    }
    payload.put("eventType", eventType);
    return payload;
  }

  /**
   * Applies one PayPal status poll to a {@code SUBMITTED} payment.
   *
   * <p>Only {@code SUBMITTED} can move; terminal states are immutable even if PayPal returns a
   * contradictory status. A poll that stays {@code SUBMITTED} refreshes the raw status and the
   * {@code updated_at} timestamp so the reconciler waits another full poll interval before asking
   * again. Every poll writes a {@code RECONCILED} ledger event.
   */
  @Transactional
  public PaymentRecord applyReconciledStatus(UUID paymentId, PayPalPayoutStatus status) {
    Objects.requireNonNull(status, "status is required");
    PaymentRecord current = requirePayment(paymentId);

    if (current.state() != PaymentState.SUBMITTED) {
      log.info(
          "Ignoring reconciled PayPal status for a non-SUBMITTED payment: paymentId={} state={}",
          paymentId,
          current.state());
      return current;
    }

    PaymentState mapped =
        PayPalStatusMapper.resolve(status.rawBatchStatus(), status.rawItemStatus());
    PaymentState next =
        mapped == PaymentState.SUBMITTED
                || stateMachine.canTransition(PaymentState.SUBMITTED, mapped)
            ? mapped
            : PaymentState.SUBMITTED;
    String rawStatus = firstPresent(status.rawItemStatus(), status.rawBatchStatus());

    PaymentRecord updated =
        paymentStore.updatePayPalState(
            paymentId,
            current.version(),
            next,
            current.paypalBatchId(),
            rawStatus,
            clock.instant());
    ledgerService.append(
        current.agentId(),
        paymentId,
        LedgerEventType.RECONCILED,
        "reconciler",
        reconciledPayload(
            "POLL",
            PaymentState.SUBMITTED,
            next,
            current.paypalBatchId(),
            status.rawBatchStatus(),
            status.rawItemStatus(),
            null));
    return updated;
  }

  private static String firstPresent(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }

  private static Map<String, Object> reconciledPayload(
      String action,
      PaymentState previousState,
      PaymentState state,
      String payoutBatchId,
      String rawBatchStatus,
      String rawItemStatus,
      Integer httpStatus) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", action);
    payload.put("previousState", previousState.name());
    payload.put("state", state.name());
    if (payoutBatchId != null && !payoutBatchId.isBlank()) {
      payload.put("payoutBatchId", payoutBatchId);
    }
    if (rawBatchStatus != null && !rawBatchStatus.isBlank()) {
      payload.put("rawBatchStatus", rawBatchStatus);
    }
    if (rawItemStatus != null && !rawItemStatus.isBlank()) {
      payload.put("rawItemStatus", rawItemStatus);
    }
    if (httpStatus != null) {
      payload.put("httpStatus", httpStatus);
    }
    return payload;
  }

  private PaymentRecord requirePayment(UUID paymentId) {
    Objects.requireNonNull(paymentId, "paymentId is required");
    return paymentStore
        .findById(paymentId)
        .orElseThrow(() -> new IllegalStateException("Payment not found: " + paymentId));
  }

  private static PaymentState initialState(PolicyOutcome outcome) {
    return switch (outcome) {
      case APPROVE -> PaymentState.APPROVED;
      case ESCALATE -> PaymentState.PENDING_APPROVAL;
      case DENY -> PaymentState.DENIED;
    };
  }

  private static BudgetTotals applyReservation(
      PolicyOutcome outcome, BudgetTotals totals, long amountMinor) {
    return switch (outcome) {
      case APPROVE -> totals.plusSpent(amountMinor);
      case ESCALATE -> totals.plusReserved(amountMinor);
      case DENY -> totals;
    };
  }

  private static Map<String, Object> decisionPayload(
      PolicyDecision decision, BudgetSnapshot budget) {
    List<Map<String, String>> reasons =
        decision.reasons().stream()
            .map(reason ->
                Map.of(
                    "code", reason.code().name(),
                    "message", reason.message()))
            .toList();
    return Map.of(
        "outcome", decision.outcome().name(),
        "reasons", reasons,
        "budgetLimitMinor", budget.limitMinor(),
        "budgetSpentMinor", budget.spentMinor(),
        "budgetReservedMinor", budget.reservedMinor());
  }

  private static Map<String, Object> paypalPayload(String payoutBatchId, String rawStatus) {
    return Map.of("payoutBatchId", payoutBatchId, "rawStatus", rawStatus);
  }
}
