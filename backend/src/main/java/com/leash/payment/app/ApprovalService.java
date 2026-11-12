package com.leash.payment.app;

import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.payment.domain.PendingApproval;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative approval workflow.
 *
 * <p>The decision itself commits in {@link PaymentTransactionService#decideApproval}. PayPal is
 * contacted only afterwards, outside that transaction, mirroring the create-payment rule. A repeat
 * approve or reject is a no-op that never calls PayPal again.
 */
@Service
public class ApprovalService {

  private final PaymentStore paymentStore;
  private final PaymentTransactionService transactions;
  private final PaymentGatewayService gateway;

  public ApprovalService(
      PaymentStore paymentStore,
      PaymentTransactionService transactions,
      PaymentGatewayService gateway) {
    this.paymentStore = paymentStore;
    this.transactions = transactions;
    this.gateway = gateway;
  }

  /** Returns pending approvals with their payments, oldest request first. */
  @Transactional(readOnly = true)
  public List<PendingApproval> listPending() {
    return paymentStore.findPendingApprovals();
  }

  /**
   * Approves a pending payment and, when the hard-rule re-check passes, submits it to PayPal.
   *
   * <p>An auto-rejection caused by the re-check is returned normally so the console can show the
   * policy reasons; it is not an error.
   */
  public ApprovalDecision approve(UUID approvalId, String actor, String note) {
    Objects.requireNonNull(approvalId, "approvalId is required");
    ApprovalDecision decision = transactions.decideApproval(approvalId, true, actor, note);
    if (decision.replayed()
        || decision.autoRejected()
        || decision.payment().state() != PaymentState.APPROVED) {
      return decision;
    }

    gateway.submitApproved(decision.payment());
    PaymentRecord latest =
        paymentStore.findById(decision.payment().id()).orElse(decision.payment());
    return new ApprovalDecision(decision.approval(), latest, false, decision.rejectionReasons());
  }

  /** Rejects a pending approval. The reservation is released because budgets are derived. */
  public ApprovalDecision reject(UUID approvalId, String actor, String note) {
    Objects.requireNonNull(approvalId, "approvalId is required");
    return transactions.decideApproval(approvalId, false, actor, note);
  }
}
