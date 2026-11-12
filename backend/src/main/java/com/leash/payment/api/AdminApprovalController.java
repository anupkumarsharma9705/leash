package com.leash.payment.api;

import com.leash.payment.app.ApprovalDecision;
import com.leash.payment.app.ApprovalService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative approval queue.
 *
 * <p>Every route runs on the admin security chain (HTTP Basic plus CSRF on writes), never on the
 * agent API key chain.
 */
@RestController
@RequestMapping("/api/v1/admin/approvals")
class AdminApprovalController {

  private final ApprovalService approvals;

  AdminApprovalController(ApprovalService approvals) {
    this.approvals = approvals;
  }

  @GetMapping
  List<AdminApprovalResponse> pending() {
    return approvals.listPending().stream().map(AdminApprovalResponse::pending).toList();
  }

  @PostMapping("/{approvalId}/approve")
  AdminApprovalResponse approve(
      @PathVariable UUID approvalId,
      @Valid @RequestBody(required = false) AdminApprovalDecisionRequest request,
      Authentication authentication) {
    ApprovalDecision decision =
        approvals.approve(approvalId, authentication.getName(), noteOf(request));
    return AdminApprovalResponse.decided(decision);
  }

  @PostMapping("/{approvalId}/reject")
  AdminApprovalResponse reject(
      @PathVariable UUID approvalId,
      @Valid @RequestBody(required = false) AdminApprovalDecisionRequest request,
      Authentication authentication) {
    ApprovalDecision decision =
        approvals.reject(approvalId, authentication.getName(), noteOf(request));
    return AdminApprovalResponse.decided(decision);
  }

  private static String noteOf(AdminApprovalDecisionRequest request) {
    return request == null ? null : request.note();
  }
}
