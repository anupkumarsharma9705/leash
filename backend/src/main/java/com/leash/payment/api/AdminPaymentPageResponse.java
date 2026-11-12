package com.leash.payment.api;

import com.leash.payment.app.PaymentPage;
import java.util.List;

/** Page envelope for {@code GET /api/v1/admin/payments}. */
public record AdminPaymentPageResponse(
    List<AdminPaymentResponse> content, int page, int size, long totalElements, int totalPages) {

  public AdminPaymentPageResponse {
    content = List.copyOf(content);
  }

  public static AdminPaymentPageResponse from(PaymentPage page) {
    return new AdminPaymentPageResponse(
        page.content().stream().map(AdminPaymentResponse::from).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }
}
