package com.leash.agent.api;

import com.leash.agent.domain.AgentNotFoundException;
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

/**
 * RFC 7807 responses for the admin agent routes.
 *
 * <p>Ordered ahead of the global payment advice so an unknown agent id is reported as {@code
 * AGENT_NOT_FOUND} here rather than as an authenticated-agent failure.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.leash.agent.api")
class AdminExceptionHandler {

  @ExceptionHandler(AgentNotFoundException.class)
  ResponseEntity<ProblemDetail> agentNotFound(
      AgentNotFoundException exception, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No agent with that id exists");
    problem.setTitle("Agent not found");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "AGENT_NOT_FOUND");
    problem.setProperty("correlationId", UUID.randomUUID().toString());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
  }
}
