package com.leash.agent.api;

import com.leash.agent.app.AgentService;
import com.leash.agent.app.AgentSummary;
import com.leash.agent.domain.CreatedAgent;
import com.leash.agent.domain.RotatedApiKey;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative agent registry.
 *
 * <p>Every route is session-authenticated with an admin credential and protected by CSRF on writes;
 * the agent API key is never consulted here. Plaintext API keys appear only in the create and
 * rotate responses.
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
class AdminAgentController {

  private final AgentService agents;

  AdminAgentController(AgentService agents) {
    this.agents = agents;
  }

  @PostMapping
  ResponseEntity<AdminCreatedAgentResponse> create(
      @Valid @RequestBody AdminCreateAgentRequest request) {
    CreatedAgent created =
        agents.createAgent(
            request.name(),
            request.weeklyLimitMinor(),
            request.autoApproveThresholdMinor(),
            request.allowedPayees());
    return ResponseEntity.status(HttpStatus.CREATED).body(AdminCreatedAgentResponse.from(created));
  }

  @GetMapping
  List<AdminAgentResponse> list() {
    return agents.listAgentSummaries().stream().map(AdminAgentController::toResponse).toList();
  }

  @PutMapping("/{agentId}/policy")
  AdminAgentResponse updatePolicy(
      @PathVariable UUID agentId,
      @Valid @RequestBody AdminUpdatePolicyRequest request,
      Authentication authentication) {
    AgentSummary summary =
        agents.updatePolicy(
            agentId,
            request.weeklyLimitMinor(),
            request.autoApproveThresholdMinor(),
            request.allowedPayees(),
            authentication.getName());
    return toResponse(summary);
  }

  @PostMapping("/{agentId}/suspend")
  AdminAgentResponse suspend(@PathVariable UUID agentId, Authentication authentication) {
    return toResponse(agents.suspendAgent(agentId, authentication.getName()));
  }

  @PostMapping("/{agentId}/resume")
  AdminAgentResponse resume(@PathVariable UUID agentId, Authentication authentication) {
    return toResponse(agents.resumeAgent(agentId, authentication.getName()));
  }

  @PostMapping("/{agentId}/rotate-key")
  AdminRotatedKeyResponse rotateKey(@PathVariable UUID agentId, Authentication authentication) {
    RotatedApiKey rotated = agents.rotateApiKey(agentId, authentication.getName());
    return AdminRotatedKeyResponse.from(rotated);
  }

  private static AdminAgentResponse toResponse(AgentSummary summary) {
    return AdminAgentResponse.from(summary.agent(), summary.spentMinor(), summary.reservedMinor());
  }
}
