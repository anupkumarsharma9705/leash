package com.leash.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BudgetTotalsTest {

  @Test
  void startsEmpty() {
    assertThat(BudgetTotals.empty().spentMinor()).isZero();
    assertThat(BudgetTotals.empty().reservedMinor()).isZero();
  }

  @Test
  void addsSpentAndReservedIndependently() {
    BudgetTotals totals = BudgetTotals.empty().plusSpent(1200).plusReserved(15000);

    assertThat(totals.spentMinor()).isEqualTo(1200);
    assertThat(totals.reservedMinor()).isEqualTo(15000);
    assertThat(totals.plusSpent(300).spentMinor()).isEqualTo(1500);
    assertThat(totals.plusReserved(700).reservedMinor()).isEqualTo(15700);
  }

  @Test
  void saturatesInsteadOfWrappingOnOverflow() {
    BudgetTotals huge = new BudgetTotals(Long.MAX_VALUE, Long.MAX_VALUE);

    assertThat(huge.plusSpent(1).spentMinor()).isEqualTo(Long.MAX_VALUE);
    assertThat(huge.plusReserved(1).reservedMinor()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsNegativeAggregates() {
    assertThatThrownBy(() -> new BudgetTotals(-1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("spentMinor");
    assertThatThrownBy(() -> new BudgetTotals(0, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservedMinor");
  }
}
