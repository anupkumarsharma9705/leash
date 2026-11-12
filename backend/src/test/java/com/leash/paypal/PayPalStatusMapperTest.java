package com.leash.paypal;

import static org.assertj.core.api.Assertions.assertThat;

import com.leash.payment.domain.PaymentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayPalStatusMapperTest {

  @ParameterizedTest
  @CsvSource({
    "SUCCESS, SUCCEEDED",
    "FAILED, FAILED",
    "PENDING, SUBMITTED",
    "UNCLAIMED, SUBMITTED",
    "RETURNED, FAILED",
    "ONHOLD, SUBMITTED",
    "BLOCKED, FAILED",
    "REFUNDED, FAILED",
    "REVERSED, FAILED",
    "CANCELED, FAILED",
    "HELD, SUBMITTED",
    "SOMETHING_NEW, SUBMITTED"
  })
  void mapsEveryPayoutItemStatus(String rawStatus, PaymentState expected) {
    assertThat(PayPalStatusMapper.fromItemStatus(rawStatus)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "DENIED, FAILED",
    "PENDING, SUBMITTED",
    "PROCESSING, SUBMITTED",
    "SUCCESS, SUBMITTED",
    "CANCELED, FAILED",
    "SOMETHING_NEW, SUBMITTED"
  })
  void mapsEveryPayoutBatchStatus(String rawStatus, PaymentState expected) {
    assertThat(PayPalStatusMapper.fromBatchStatus(rawStatus)).isEqualTo(expected);
  }

  @Test
  void batchSuccessIsNotProofOfItemSuccess() {
    assertThat(PayPalStatusMapper.resolve("SUCCESS", null)).isEqualTo(PaymentState.SUBMITTED);
    assertThat(PayPalStatusMapper.resolve("SUCCESS", "ONHOLD")).isEqualTo(PaymentState.SUBMITTED);
    assertThat(PayPalStatusMapper.resolve("SUCCESS", "UNCLAIMED"))
        .isEqualTo(PaymentState.SUBMITTED);
  }

  @Test
  void itemStatusTakesPrecedenceOverBatchStatus() {
    assertThat(PayPalStatusMapper.resolve("PENDING", "SUCCESS")).isEqualTo(PaymentState.SUCCEEDED);
    assertThat(PayPalStatusMapper.resolve("SUCCESS", "FAILED")).isEqualTo(PaymentState.FAILED);
  }

  @Test
  void normalizesCaseAndWhitespace() {
    assertThat(PayPalStatusMapper.fromItemStatus(" success ")).isEqualTo(PaymentState.SUCCEEDED);
    assertThat(PayPalStatusMapper.fromBatchStatus(" denied ")).isEqualTo(PaymentState.FAILED);
  }

  @Test
  void nullStatusesStayPending() {
    assertThat(PayPalStatusMapper.fromItemStatus(null)).isEqualTo(PaymentState.SUBMITTED);
    assertThat(PayPalStatusMapper.fromBatchStatus(null)).isEqualTo(PaymentState.SUBMITTED);
    assertThat(PayPalStatusMapper.resolve(null, null)).isEqualTo(PaymentState.SUBMITTED);
  }
}
