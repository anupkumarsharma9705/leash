package com.leash.payment.app;

import java.util.List;
import java.util.Objects;

/**
 * One page of administrative payments.
 *
 * <p>{@code totalPages} is zero for an empty result, matching the page envelope the console
 * displays.
 */
public record PaymentPage(List<AdminPaymentRow> content, int page, int size, long totalElements) {

  public PaymentPage {
    Objects.requireNonNull(content, "content is required");
    content = List.copyOf(content);
    if (page < 0) {
      throw new IllegalArgumentException("page must not be negative");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
    if (totalElements < 0) {
      throw new IllegalArgumentException("totalElements must not be negative");
    }
  }

  public int totalPages() {
    if (totalElements == 0) {
      return 0;
    }
    long pages = (totalElements - 1) / size + 1;
    return pages > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pages;
  }
}
