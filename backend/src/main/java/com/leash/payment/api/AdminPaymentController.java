package com.leash.payment.api;

import com.leash.payment.app.AdminPaymentService;
import com.leash.payment.app.PaymentListFilter;
import com.leash.payment.app.PaymentRefreshResult;
import com.leash.payment.domain.PaymentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative payment ledger, timeline, and manual refresh.
 *
 * <p>Every route runs on the admin security chain (HTTP Basic plus CSRF on the refresh write),
 * never on the agent API key chain. Responses are DTO records; entities never leave the service.
 */
@RestController
@RequestMapping("/api/v1/admin/payments")
class AdminPaymentController {

  private final AdminPaymentService payments;

  AdminPaymentController(AdminPaymentService payments) {
    this.payments = payments;
  }

  @GetMapping
  AdminPaymentPageResponse list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(required = false) PaymentState status,
      @RequestParam(required = false) UUID agentId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to) {
    return AdminPaymentPageResponse.from(
        payments.list(page, size, new PaymentListFilter(status, agentId, from, to)));
  }

  @GetMapping("/{paymentId}/events")
  List<AdminPaymentEventResponse> events(@PathVariable UUID paymentId) {
    return payments.events(paymentId).stream().map(AdminPaymentEventResponse::from).toList();
  }

  @PostMapping("/{paymentId}/refresh")
  AdminPaymentRefreshResponse refresh(@PathVariable UUID paymentId) {
    PaymentRefreshResult result = payments.refresh(paymentId);
    return AdminPaymentRefreshResponse.from(result);
  }
}
