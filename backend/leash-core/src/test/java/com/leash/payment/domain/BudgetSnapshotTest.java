package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BudgetSnapshotTest {

  @Test
  void computesRemainingFromSpentAndReserved() {
    assertThat(new BudgetSnapshot(50_000, 1200, 15_000).remainingMinor()).isEqualTo(33_800);
  }

  @Test
  void remainingIsZeroAtExactlyTheLimit() {
    assertThat(new BudgetSnapshot(50_000, 50_000, 0).remainingMinor()).isZero();
    assertThat(new BudgetSnapshot(50_000, 20_000, 30_000).remainingMinor()).isZero();
  }

  @Test
  void remainingSaturatesAtZeroWhenOverBudget() {
    assertThat(new BudgetSnapshot(50_000, 60_000, 0).remainingMinor()).isZero();
    assertThat(new BudgetSnapshot(50_000, 40_000, 20_000).remainingMinor()).isZero();
  }

  @Test
  void remainingSaturatesWhenAggregatesOverflow() {
    assertThat(new BudgetSnapshot(50_000, Long.MAX_VALUE, Long.MAX_VALUE).remainingMinor())
        .isZero();
  }

  @Test
  void rejectsNegativeValues() {
    assertThatThrownBy(() -> new BudgetSnapshot(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limitMinor");
    assertThatThrownBy(() -> new BudgetSnapshot(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spentMinor");
    assertThatThrownBy(() -> new BudgetSnapshot(0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservedMinor");
  }
}
