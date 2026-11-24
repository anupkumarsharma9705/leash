package com.leash.webhook.api;

import com.leash.paypal.PayPalWebhookVerificationException;
import com.leash.webhook.app.WebhookProcessingException;
import com.leash.webhook.domain.WebhookRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 responses for the webhook boundary. */
@RestControllerAdvice
class WebhookExceptionHandler {

  @ExceptionHandler(WebhookRejectedException.class)
  ResponseEntity<ProblemDetail> rejected(
      WebhookRejectedException exception, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Webhook rejected");
    problem.setProperty("code", exception.code());
    addCorrelationId(problem, request);
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(PayPalWebhookVerificationException.class)
  ResponseEntity<ProblemDetail> verificationUnavailable(HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "PayPal webhook verification is temporarily unavailable");
    problem.setTitle("Webhook verification unavailable");
    problem.setProperty("code", "WEBHOOK_VERIFICATION_UNAVAILABLE");
    addCorrelationId(problem, request);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
  }

  @ExceptionHandler(WebhookProcessingException.class)
  ResponseEntity<ProblemDetail> processingFailed(HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The verified webhook could not be applied");
    problem.setTitle("Webhook processing failed");
    problem.setProperty("code", "WEBHOOK_PROCESSING_FAILED");
    addCorrelationId(problem, request);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
  }

  private static void addCorrelationId(ProblemDetail problem, HttpServletRequest request) {
    String transmissionId = request.getHeader("PAYPAL-TRANSMISSION-ID");
    if (transmissionId != null && !transmissionId.isBlank()) {
      problem.setProperty("correlationId", transmissionId);
    }
  }
}
