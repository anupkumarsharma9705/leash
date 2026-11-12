package com.leash.payment.app;

import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.common.money.Money;
import com.leash.common.money.SupportedCurrency;
import com.leash.payment.domain.PaymentCommand;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.paypal.PayPalOAuthException;
import com.leash.paypal.PayPalPayoutException;
import com.leash.paypal.PayPalPayoutsClient;
import com.leash.paypal.PayoutRequest;
import com.leash.paypal.PayoutSubmission;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the committed-before-PayPal rule.
 *
 * <p>TX-A commits the decision; only then is PayPal called, outside every transaction. TX-B records
 * the identifiers. A transient PayPal failure leaves the payment {@code APPROVED} so the reconciler
 * can retry with the same {@code PayPal-Request-Id}; a definitive rejection marks it {@code
 * FAILED}.
 */
@Service
public class PaymentGatewayService {

  private static final Logger log = LoggerFactory.getLogger(PaymentGatewayService.class);

  private final PaymentTransactionService transactions;
  private final PayPalPayoutsClient payoutsClient;

  public PaymentGatewayService(
      PaymentTransactionService transactions, PayPalPayoutsClient payoutsClient) {
    this.transactions = transactions;
    this.payoutsClient = payoutsClient;
  }

  public PaymentResult createPayment(
      AuthenticatedAgent agent, PaymentCommand command, String idempotencyKey) {
    Objects.requireNonNull(agent, "agent is required");
    PreparedPayment prepared = transactions.prepare(agent.agentId(), command, idempotencyKey);
    if (prepared.replayed() || prepared.record().state() != PaymentState.APPROVED) {
      return PaymentResult.from(prepared.record(), prepared.replayed());
    }
    return submit(prepared.record(), false);
  }

  /**
   * Retries submission of a payment left {@code APPROVED} by a crash or a transient PayPal failure.
   *
   * <p>The same payment id is reused as the PayPal idempotency key, so the retry cannot create a
   * second payout.
   */
  public PaymentResult retryApproved(PaymentRecord record) {
    Objects.requireNonNull(record, "record is required");
    if (record.state() != PaymentState.APPROVED) {
      return PaymentResult.from(record, false);
    }
    return submit(record, true);
  }

  /**
   * Submits a payment that an admin just approved.
   *
   * <p>The approval transaction has already committed, so this call runs outside every transaction
   * and then records the PayPal identifiers in TX-B. A transient failure leaves the payment {@code
   * APPROVED} for the reconciler; a definitive rejection marks it {@code FAILED}.
   */
  public PaymentResult submitApproved(PaymentRecord record) {
    Objects.requireNonNull(record, "record is required");
    if (record.state() != PaymentState.APPROVED) {
      return PaymentResult.from(record, false);
    }
    return submit(record, false);
  }

  private PaymentResult submit(PaymentRecord record, boolean reconciled) {
    PayoutRequest request =
        new PayoutRequest(
            record.id().toString(),
            record.payeeEmail(),
            new Money(record.amountMinor(), SupportedCurrency.valueOf(record.currency())),
            record.memo());
    try {
      PayoutSubmission submission = payoutsClient.submit(request);
      PaymentRecord updated =
          reconciled
              ? transactions.markSubmittedByReconciler(record.id(), submission)
              : transactions.markSubmitted(record.id(), submission);
      return PaymentResult.from(updated, false);
    } catch (PayPalOAuthException exception) {
      // A missing or unusable OAuth configuration is transient: the decision already committed as
      // APPROVED, so record nothing in TX-B and let the reconciler retry once credentials exist.
      log.warn(
          "PayPal OAuth unavailable; payment stays APPROVED for the reconciler: paymentId={}",
          record.id());
      return PaymentResult.from(record, false);
    } catch (PayPalPayoutException exception) {
      if (isPermanent(exception)) {
        log.error(
            "PayPal rejected the payout; payment marked FAILED: paymentId={} httpStatus={} debugId={}",
            record.id(),
            exception.httpStatus(),
            exception.debugId());
        PaymentRecord updated =
            reconciled
                ? transactions.markSubmissionFailedByReconciler(
                    record.id(), exception.httpStatus(), exception.debugId())
                : transactions.markSubmissionFailed(
                    record.id(), exception.httpStatus(), exception.debugId());
        return PaymentResult.from(updated, false);
      }
      log.warn(
          "PayPal submission unavailable; payment stays APPROVED for the reconciler:"
              + " paymentId={} httpStatus={} debugId={}",
          record.id(),
          exception.httpStatus(),
          exception.debugId());
      return PaymentResult.from(record, false);
    }
  }

  private static boolean isPermanent(PayPalPayoutException exception) {
    Integer status = exception.httpStatus();
    return status != null && status >= 400 && status < 500 && status != 429;
  }
}
