package com.leash.demoagent;

import com.leash.agent.app.AgentService;
import com.leash.agent.app.AgentSummary;
import com.leash.agent.domain.CreatedAgent;
import com.leash.agent.domain.RotatedApiKey;
import com.leash.policy.AgentPolicy;
import com.leash.policy.AgentStatus;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Provisions the demo agent at startup.
 *
 * <p>If an agent with the configured name already exists, its policy is aligned with the demo
 * settings, it is resumed if suspended, and its key is rotated so the in-memory client always has a
 * valid credential. The plaintext key never leaves memory.
 */
@Component
@Profile("demo")
public class DemoAgentSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeder.class);
  private static final String ACTOR = "demo-seeder";

  private final AgentService agents;
  private final DemoAgentCredentials credentials;
  private final DemoProperties properties;
  private final DemoInvoiceLoader invoices;

  public DemoAgentSeeder(
      AgentService agents,
      DemoAgentCredentials credentials,
      DemoProperties properties,
      DemoInvoiceLoader invoices) {
    this.agents = agents;
    this.credentials = credentials;
    this.properties = properties;
    this.invoices = invoices;
  }

  @Override
  public void run(ApplicationArguments args) {
    AgentSummary existing =
        agents.listAgentSummaries().stream()
            .filter(summary -> summary.agent().name().equals(properties.agentName()))
            .findFirst()
            .orElse(null);

    if (existing == null) {
      createAgent();
    } else {
      reprovision(existing);
    }
  }

  private void createAgent() {
    CreatedAgent created =
        agents.createAgent(
            properties.agentName(),
            properties.weeklyLimitMinor(),
            properties.autoApproveThresholdMinor(),
            properties.allowedPayees());
    UUID agentId = created.agent().id();
    credentials.publish(new DemoAgentIdentity(agentId, properties.agentName(), created.apiKey()));
    log.info(
        "Seeded demo agent: agentId={} invoices={} allowedPayees={}",
        agentId,
        invoices.invoices().size(),
        properties.allowedPayees().size());
  }

  private void reprovision(AgentSummary existing) {
    UUID agentId = existing.agent().id();
    alignPolicy(agentId, existing.agent().policy());
    if (existing.agent().status() != AgentStatus.ACTIVE) {
      agents.resumeAgent(agentId, ACTOR);
    }
    RotatedApiKey rotated = agents.rotateApiKey(agentId, ACTOR);
    credentials.publish(
        new DemoAgentIdentity(rotated.agentId(), properties.agentName(), rotated.apiKey()));
    log.info(
        "Re-provisioned demo agent: agentId={} invoices={} allowedPayees={}",
        agentId,
        invoices.invoices().size(),
        properties.allowedPayees().size());
  }

  private void alignPolicy(UUID agentId, AgentPolicy current) {
    AgentPolicy desired =
        new AgentPolicy(
            properties.weeklyLimitMinor(),
            properties.autoApproveThresholdMinor(),
            new LinkedHashSet<>(properties.allowedPayees()));
    if (samePolicy(current, desired)) {
      return;
    }
    agents.updatePolicy(
        agentId,
        desired.weeklyLimitMinor(),
        desired.autoApproveThresholdMinor(),
        desired.allowedPayees(),
        ACTOR);
    log.info("Aligned demo policy: agentId={}", agentId);
  }

  private static boolean samePolicy(AgentPolicy current, AgentPolicy desired) {
    Set<String> currentPayees = current.allowedPayees();
    Set<String> desiredPayees = desired.allowedPayees();
    return current.weeklyLimitMinor() == desired.weeklyLimitMinor()
        && current.autoApproveThresholdMinor() == desired.autoApproveThresholdMinor()
        && currentPayees.equals(desiredPayees);
  }
}
