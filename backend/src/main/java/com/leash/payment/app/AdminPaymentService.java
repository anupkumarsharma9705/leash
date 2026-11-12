package com.leash.payment.app;

import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEvent;
import com.leash.payment.domain.InvalidPaymentQueryException;
import com.leash.payment.domain.PaymentNotFoundException;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.paypal.PayPalPayoutStatus;
import com.leash.paypal.PayPalPayoutsClient;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side and manual-refresh operations for the admin payment console.
 *
 * <p>The list is two queries regardless of page size: one page query that joins the agent name and
 * one count query. The timeline reuses the append-only ledger. Refresh polls PayPal outside every
 * transaction and applies the result through the same path the reconciler uses, so a manual poll
 * can never bypass the state machine.
 */
@Service
public class AdminPaymentService {

  public static final int MAX_PAGE_SIZE = 100;

  private final PaymentStore paymentStore;
  private final LedgerService ledgerService;
  private final PaymentTransactionService transactions;
  private final PayPalPayoutsClient payoutsClient;

  public AdminPaymentService(
      PaymentStore paymentStore,
      LedgerService ledgerService,
      PaymentTransactionService transactions,
      PayPalPayoutsClient payoutsClient) {
    this.paymentStore = paymentStore;
    this.ledgerService = ledgerService;
    this.transactions = transactions;
    this.payoutsClient = payoutsClient;
  }

  /**
   * Returns one newest-first page.
   *
   * @param page zero-based page index
   * @param size page size, from 1 to {@value #MAX_PAGE_SIZE}
   * @throws InvalidPaymentQueryException when the paging values are out of range
   */
  @Transactional(readOnly = true)
  public PaymentPage list(int page, int size, PaymentListFilter filter) {
    if (page < 0) {
      throw new InvalidPaymentQueryException("page must not be negative");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new InvalidPaymentQueryException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
    PaymentListFilter effectiveFilter = filter == null ? PaymentListFilter.none() : filter;
    long totalElements = paymentStore.count(effectiveFilter);
    List<AdminPaymentRow> content =
        paymentStore.findPage(effectiveFilter, (long) page * size, size);
    return new PaymentPage(content, page, size, totalElements);
  }

  /**
   * Returns the ledger timeline for one payment, oldest event first.
   *
   * @throws PaymentNotFoundException when the payment does not exist
   */
  @Transactional(readOnly = true)
  public List<LedgerEvent> events(UUID paymentId) {
    requirePayment(paymentId);
    return ledgerService.eventsForPayment(paymentId);
  }

  /**
   * Polls PayPal once for a submitted payment.
   *
   * <p>Only {@code SUBMITTED} rows with a payout batch id are polled. Everything else is a no-op
   * that returns the current row and a reason, because refresh must not resubmit or move a terminal
   * payment. The PayPal call happens before any transaction; applying the status happens after it.
   *
   * @throws PaymentNotFoundException when the payment does not exist
   */
  public PaymentRefreshResult refresh(UUID paymentId) {
    PaymentRecord current = requirePayment(paymentId);
    if (current.state() != PaymentState.SUBMITTED) {
      return new PaymentRefreshResult(
          current,
          false,
          "Only SUBMITTED payments can be polled; current state is " + current.state());
    }
    if (current.paypalBatchId() == null || current.paypalBatchId().isBlank()) {
      return new PaymentRefreshResult(current, false, "Payment has no PayPal batch id");
    }

    PayPalPayoutStatus status = payoutsClient.lookup(current.paypalBatchId());
    PaymentRecord updated = transactions.applyReconciledStatus(paymentId, status);
    return new PaymentRefreshResult(updated, true, null);
  }

  private PaymentRecord requirePayment(UUID paymentId) {
    Objects.requireNonNull(paymentId, "paymentId is required");
    return paymentStore.findById(paymentId).orElseThrow(PaymentNotFoundException::new);
  }
}
