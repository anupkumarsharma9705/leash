package com.leash.payment.api;

import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.payment.app.PaymentGatewayService;
import com.leash.payment.app.PaymentQueryService;
import com.leash.payment.app.PaymentResult;
import com.leash.payment.domain.PaymentCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent-facing payment creation and read endpoints. Authentication is handled by the API-key
 * filter.
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

  static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

  private final PaymentGatewayService gateway;
  private final PaymentQueryService queries;

  PaymentController(PaymentGatewayService gateway, PaymentQueryService queries) {
    this.gateway = gateway;
    this.queries = queries;
  }

  @PostMapping
  ResponseEntity<PaymentResponse> create(
      @AuthenticationPrincipal AuthenticatedAgent agent,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreatePaymentRequest request) {
    PaymentCommand command =
        new PaymentCommand(
            request.payeeEmail(), request.amountMinor(), request.currency(), request.memo());
    PaymentResult result = gateway.createPayment(agent, command, idempotencyKey);

    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) {
      response.header(IDEMPOTENT_REPLAYED_HEADER, "true");
    }
    return response.body(PaymentResponse.from(result));
  }

  @GetMapping("/{paymentId}")
  PaymentResponse find(
      @AuthenticationPrincipal AuthenticatedAgent agent, @PathVariable UUID paymentId) {
    return PaymentResponse.from(queries.findOwnedPayment(agent.agentId(), paymentId));
  }
}
