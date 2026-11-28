package com.leash.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.leash.payment.domain.BudgetSnapshot;
import com.leash.payment.domain.PaymentRecord;
import com.leash.payment.domain.PaymentState;
import com.leash.paypal.PayPalPayoutStatus;
import com.leash.paypal.PayPalPayoutsClient;
import com.leash.paypal.PayoutRequest;
import com.leash.paypal.PayoutSubmission;
import com.leash.policy.PolicyOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/** Unit coverage for the gateway's lost-race recovery around TX-B. */
class PaymentGatewayServiceTest {

  private static final UUID PAYMENT_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

  @Test
  void returnsTheStoredStateWhenTxBLosesAnOptimisticLockRace() {
    PaymentRecord approved = payment(PaymentState.APPROVED, 0, null);
    PaymentRecord submitted = payment(PaymentState.SUBMITTED, 1, "batch-1");
    LosingTransactions transactions = new LosingTransactions(submitted);
    PaymentGatewayService gateway = new PaymentGatewayService(transactions, new StubPayouts());

    PaymentResult result = gateway.submitApproved(approved);

    assertThat(transactions.markSubmittedAttempted).isTrue();
    assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
    assertThat(result.state()).isEqualTo(PaymentState.SUBMITTED);
  }

  private static PaymentRecord payment(PaymentState state, long version, String batchId) {
    return new PaymentRecord(
        PAYMENT_ID,
        UUID.randomUUID(),
        "invoice-1",
        "fingerprint",
        "alice@example.com",
        1_200,
        "USD",
        "Invoice 1",
        state,
        PolicyOutcome.APPROVE,
        List.of(),
        batchId,
        batchId == null ? null : "PENDING",
        new BudgetSnapshot(50_000, 1_200, 0),
        version,
        NOW,
        NOW);
  }

  /** Simulates a concurrent webhook winning TX-B after PayPal accepted the payout. */
  private static final class LosingTransactions extends PaymentTransactionService {

    private final PaymentRecord current;
    private boolean markSubmittedAttempted;

    private LosingTransactions(PaymentRecord current) {
      super(null, null, null, null, null, null);
      this.current = current;
    }

    @Override
    public PaymentRecord markSubmitted(UUID paymentId, PayoutSubmission submission) {
      markSubmittedAttempted = true;
      throw new OptimisticLockingFailureException("concurrent update");
    }

    @Override
    public PaymentRecord findPayment(UUID paymentId) {
      return current;
    }
  }

  private static final class StubPayouts implements PayPalPayoutsClient {

    @Override
    public PayoutSubmission submit(PayoutRequest request) {
      return new PayoutSubmission("batch-1", "PENDING");
    }

    @Override
    public PayPalPayoutStatus lookup(String payoutBatchId) {
      throw new UnsupportedOperationException();
    }
  }
}
