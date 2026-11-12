package com.leash.demoagent.api;

import com.leash.demoagent.DemoAgentNotReadyException;
import com.leash.demoagent.DemoClientException;
import com.leash.demoagent.DemoLlmNotConfiguredException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 7807 responses for the demo runner, without leaking credentials or upstream bodies. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.leash.demoagent")
class DemoExceptionHandler {

  @ExceptionHandler(DemoLlmNotConfiguredException.class)
  ResponseEntity<ProblemDetail> llmNotConfigured(
      DemoLlmNotConfiguredException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_IMPLEMENTED,
        "LLM mode is not configured",
        exception.getMessage(),
        "LLM_NOT_CONFIGURED",
        request,
        null);
  }

  @ExceptionHandler(DemoAgentNotReadyException.class)
  ResponseEntity<ProblemDetail> agentNotReady(
      DemoAgentNotReadyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Demo agent is not ready",
        exception.getMessage(),
        "DEMO_AGENT_NOT_READY",
        request,
        null);
  }

  @ExceptionHandler(DemoClientException.class)
  ResponseEntity<ProblemDetail> upstreamFailure(
      DemoClientException exception, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
    problem.setTitle("Demo agent call failed");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty(
        "code",
        exception.code() == null || exception.code().isBlank()
            ? "DEMO_UPSTREAM_FAILED"
            : exception.code());
    problem.setProperty("correlationId", UUID.randomUUID().toString());
    if (exception.httpStatus() != null) {
      problem.setProperty("upstreamStatus", exception.httpStatus());
    }
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status,
      String title,
      String detail,
      String code,
      HttpServletRequest request,
      Integer upstreamStatus) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", UUID.randomUUID().toString());
    if (upstreamStatus != null) {
      problem.setProperty("upstreamStatus", upstreamStatus);
    }
    return ResponseEntity.status(status).body(problem);
  }
}
