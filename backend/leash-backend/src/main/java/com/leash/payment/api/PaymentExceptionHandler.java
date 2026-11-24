package com.leash.payment.api;

import com.leash.agent.domain.AgentNotFoundException;
import com.leash.payment.domain.ApprovalAlreadyDecidedException;
import com.leash.payment.domain.ApprovalNotFoundException;
import com.leash.payment.domain.IdempotencyKeyReusedException;
import com.leash.payment.domain.InvalidIdempotencyKeyException;
import com.leash.payment.domain.InvalidPaymentQueryException;
import com.leash.payment.domain.MissingIdempotencyKeyException;
import com.leash.payment.domain.PaymentNotFoundException;
import com.leash.paypal.PayPalPayoutException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** RFC 7807 responses for the agent payment API. */
@RestControllerAdvice
class PaymentExceptionHandler {

  @ExceptionHandler(MissingIdempotencyKeyException.class)
  ResponseEntity<ProblemDetail> missingIdempotencyKey(
      MissingIdempotencyKeyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Missing Idempotency-Key",
        exception.getMessage(),
        "IDEMPOTENCY_KEY_REQUIRED",
        null,
        request);
  }

  @ExceptionHandler(InvalidIdempotencyKeyException.class)
  ResponseEntity<ProblemDetail> invalidIdempotencyKey(
      InvalidIdempotencyKeyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid Idempotency-Key",
        exception.getMessage(),
        "INVALID_IDEMPOTENCY_KEY",
        null,
        request);
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ProblemDetail> idempotencyKeyReused(
      IdempotencyKeyReusedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "Idempotency key reused",
        exception.getMessage(),
        "IDEMPOTENCY_KEY_REUSED",
        null,
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validationFailed(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<String> details =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();
    return problem(
        HttpStatus.BAD_REQUEST,
        "Validation failed",
        "One or more request fields are invalid",
        "VALIDATION_FAILED",
        details,
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid request body",
        "The request body is missing or is not valid JSON",
        "INVALID_REQUEST_BODY",
        null,
        request);
  }

  @ExceptionHandler(AgentNotFoundException.class)
  ResponseEntity<ProblemDetail> agentNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Agent not found",
        "The authenticated agent no longer exists",
        "AGENT_NOT_FOUND",
        null,
        request);
  }

  @ExceptionHandler(PaymentNotFoundException.class)
  ResponseEntity<ProblemDetail> paymentNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Payment not found",
        "No payment with that id exists",
        "PAYMENT_NOT_FOUND",
        null,
        request);
  }

  @ExceptionHandler(InvalidPaymentQueryException.class)
  ResponseEntity<ProblemDetail> invalidPaymentQuery(
      InvalidPaymentQueryException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid payment query",
        exception.getMessage(),
        "INVALID_QUERY",
        null,
        request);
  }

  @ExceptionHandler(PayPalPayoutException.class)
  ResponseEntity<ProblemDetail> paypalUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_GATEWAY,
        "PayPal unavailable",
        "PayPal could not be reached; the payment was not changed",
        "PAYPAL_UNAVAILABLE",
        null,
        request);
  }

  @ExceptionHandler(ApprovalNotFoundException.class)
  ResponseEntity<ProblemDetail> approvalNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Approval not found",
        "No approval with that id exists",
        "APPROVAL_NOT_FOUND",
        null,
        request);
  }

  @ExceptionHandler(ApprovalAlreadyDecidedException.class)
  ResponseEntity<ProblemDetail> approvalAlreadyDecided(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "Approval already decided",
        "This approval was already decided in the opposite direction",
        "APPROVAL_ALREADY_DECIDED",
        null,
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> invalidPathValue(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "Invalid path value",
        "The request path contains an invalid value",
        "INVALID_PATH_VALUE",
        null,
        request);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status,
      String title,
      String detail,
      String code,
      List<String> details,
      HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", UUID.randomUUID().toString());
    if (details != null) {
      problem.setProperty("details", details);
    }
    return ResponseEntity.status(status).body(problem);
  }
}
