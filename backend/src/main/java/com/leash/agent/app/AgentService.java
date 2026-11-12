package com.leash.agent.app;

import com.leash.agent.domain.Agent;
import com.leash.agent.domain.AgentNotFoundException;
import com.leash.agent.domain.AgentSuspendedException;
import com.leash.agent.domain.ApiKeyGenerator;
import com.leash.agent.domain.ApiKeyHasher;
import com.leash.agent.domain.AuthenticatedAgent;
import com.leash.agent.domain.CreatedAgent;
import com.leash.agent.domain.InvalidApiKeyException;
import com.leash.agent.domain.RotatedApiKey;
import com.leash.ledger.app.LedgerService;
import com.leash.ledger.domain.LedgerEventType;
import com.leash.policy.AgentPolicy;
import com.leash.policy.AgentStatus;
import com.leash.policy.BudgetPeriod;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers agents, authenticates their API keys, and exposes the administrative lifecycle.
 *
 * <p>Plaintext keys are returned exactly once from {@link #createAgent} or {@link #rotateApiKey}.
 * Only the SHA-256 hash and an eight-character prefix are stored. Authentication compares hashes in
 * constant time and never logs the presented key.
 *
 * <p>Every administrative mutation locks the agent row first and appends a ledger fact in the same
 * transaction. The lock is the same one the payment decision takes, so a policy edit cannot land in
 * the middle of a budget check.
 */
@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  private final AgentStore store;
  private final ApiKeyGenerator keyGenerator;
  private final ApiKeyHasher hasher;
  private final AgentBudgetReader budgetReader;
  private final LedgerService ledgerService;
  private final Clock clock;

  public AgentService(
      AgentStore store,
      ApiKeyGenerator keyGenerator,
      ApiKeyHasher hasher,
      AgentBudgetReader budgetReader,
      LedgerService ledgerService,
      Clock clock) {
    this.store = store;
    this.keyGenerator = keyGenerator;
    this.hasher = hasher;
    this.budgetReader = budgetReader;
    this.ledgerService = ledgerService;
    this.clock = clock;
  }

  @Transactional
  public CreatedAgent createAgent(
      String name,
      long weeklyLimitMinor,
      long autoApproveThresholdMinor,
      Collection<String> allowedPayees) {
    Objects.requireNonNull(allowedPayees, "allowedPayees is required");
    AgentPolicy policy =
        new AgentPolicy(
            weeklyLimitMinor, autoApproveThresholdMinor, new LinkedHashSet<>(allowedPayees));

    Instant now = clock.instant();
    Agent agent = new Agent(UUID.randomUUID(), name, AgentStatus.ACTIVE, policy, now);

    String plaintextKey = keyGenerator.generate();
    store.insertAgent(agent);
    store.insertApiKey(
        new ApiKeyRecord(
            UUID.randomUUID(),
            agent.id(),
            hasher.hash(plaintextKey),
            keyGenerator.prefix(plaintextKey),
            now,
            null));

    log.info("Registered agent: agentId={} name={}", agent.id(), agent.name());
    return new CreatedAgent(agent, plaintextKey);
  }

  /** Lists every agent with its live current-week spend. */
  @Transactional(readOnly = true)
  public List<AgentSummary> listAgentSummaries() {
    List<Agent> agents = store.listAgents();
    if (agents.isEmpty()) {
      return List.of();
    }
    Map<UUID, AgentBudget> budgets = budgetReader.totalsFor(BudgetPeriod.current(clock));
    return agents.stream()
        .map(agent -> {
          AgentBudget budget = budgets.getOrDefault(agent.id(), AgentBudget.empty());
          return new AgentSummary(agent, budget.spentMinor(), budget.reservedMinor());
        })
        .toList();
  }

  /**
   * Replaces an agent's policy and allow-list.
   *
   * <p>The agent row is locked for the edit, matching the payment decision's lock, so a request
   * cannot evaluate an old policy while the new one commits.
   *
   * @return the updated agent with its live current-week budget
   * @throws AgentNotFoundException when the agent no longer exists
   */
  @Transactional
  public AgentSummary updatePolicy(
      UUID agentId,
      long weeklyLimitMinor,
      long autoApproveThresholdMinor,
      Collection<String> allowedPayees,
      String actor) {
    Objects.requireNonNull(agentId, "agentId is required");
    Objects.requireNonNull(allowedPayees, "allowedPayees is required");
    String decidedBy = requireActor(actor);

    Agent current = store.lockAgent(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
    AgentPolicy policy =
        new AgentPolicy(
            weeklyLimitMinor, autoApproveThresholdMinor, new LinkedHashSet<>(allowedPayees));
    store.updatePolicy(agentId, policy, clock.instant());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousWeeklyLimitMinor", current.policy().weeklyLimitMinor());
    payload.put("weeklyLimitMinor", policy.weeklyLimitMinor());
    payload.put("previousAutoApproveThresholdMinor", current.policy().autoApproveThresholdMinor());
    payload.put("autoApproveThresholdMinor", policy.autoApproveThresholdMinor());
    payload.put("previousAllowedPayees", sorted(current.policy().allowedPayees()));
    payload.put("allowedPayees", sorted(policy.allowedPayees()));
    ledgerService.append(agentId, null, LedgerEventType.POLICY_CHANGED, decidedBy, payload);

    log.info("Updated policy: agentId={} actor={}", agentId, decidedBy);
    Agent updated =
        new Agent(current.id(), current.name(), current.status(), policy, current.createdAt());
    return summaryOf(updated);
  }

  /** Suspends an agent. Suspending an already suspended agent is a no-op. */
  @Transactional
  public AgentSummary suspendAgent(UUID agentId, String actor) {
    return changeStatus(agentId, AgentStatus.SUSPENDED, actor);
  }

  /** Resumes an agent. Resuming an already active agent is a no-op. */
  @Transactional
  public AgentSummary resumeAgent(UUID agentId, String actor) {
    return changeStatus(agentId, AgentStatus.ACTIVE, actor);
  }

  /**
   * Revokes every active key for the agent and issues a new one.
   *
   * @return the new plaintext key, available only in this result
   * @throws AgentNotFoundException when the agent no longer exists
   */
  @Transactional
  public RotatedApiKey rotateApiKey(UUID agentId, String actor) {
    Objects.requireNonNull(agentId, "agentId is required");
    String decidedBy = requireActor(actor);

    store.lockAgent(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
    Instant now = clock.instant();
    String plaintextKey = keyGenerator.generate();
    int revokedKeyCount = store.revokeActiveApiKeys(agentId, now);
    store.insertApiKey(
        new ApiKeyRecord(
            UUID.randomUUID(),
            agentId,
            hasher.hash(plaintextKey),
            keyGenerator.prefix(plaintextKey),
            now,
            null));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("prefix", keyGenerator.prefix(plaintextKey));
    payload.put("revokedKeyCount", revokedKeyCount);
    ledgerService.append(agentId, null, LedgerEventType.API_KEY_ROTATED, decidedBy, payload);

    log.info(
        "Rotated API key: agentId={} actor={} revokedKeyCount={}",
        agentId,
        decidedBy,
        revokedKeyCount);
    return new RotatedApiKey(agentId, plaintextKey);
  }

  /**
   * Returns an agent's current status and policy without locking.
   *
   * @throws AgentNotFoundException when the agent no longer exists
   */
  @Transactional(readOnly = true)
  public Agent findAgent(UUID agentId) {
    Objects.requireNonNull(agentId, "agentId is required");
    return store.findAgent(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
  }

  /**
   * Locks an agent for a payment decision and returns its current policy.
   *
   * <p>The row lock is held until the caller's transaction commits, serializing budget checks for
   * one agent.
   *
   * @throws AgentNotFoundException when the agent has been deleted since authentication
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Agent lockAgent(UUID agentId) {
    return store.lockAgent(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
  }

  /**
   * Authenticates a presented API key.
   *
   * @throws InvalidApiKeyException when the key is malformed, unknown, or revoked
   * @throws AgentSuspendedException when the key is valid but its agent is suspended
   */
  public AuthenticatedAgent authenticate(String presentedKey) {
    if (!keyGenerator.isWellFormed(presentedKey)) {
      log.warn("Rejected agent authentication: malformed API key");
      throw new InvalidApiKeyException();
    }

    String prefix = keyGenerator.prefix(presentedKey);
    List<ApiKeyCandidate> candidates = store.findApiKeyCandidates(prefix);
    for (ApiKeyCandidate candidate : candidates) {
      if (candidate.revokedAt() == null && hasher.matches(presentedKey, candidate.keyHash())) {
        if (candidate.agentStatus() != AgentStatus.ACTIVE) {
          log.warn("Rejected agent authentication: agentId={} is suspended", candidate.agentId());
          throw new AgentSuspendedException(candidate.agentId());
        }
        store.markApiKeyUsed(candidate.apiKeyId(), clock.instant());
        return new AuthenticatedAgent(candidate.agentId(), candidate.agentName());
      }
    }

    log.warn("Rejected agent authentication: unknown or revoked API key prefix={}", prefix);
    throw new InvalidApiKeyException();
  }

  private AgentSummary changeStatus(UUID agentId, AgentStatus target, String actor) {
    Objects.requireNonNull(agentId, "agentId is required");
    String decidedBy = requireActor(actor);

    Agent current = store.lockAgent(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
    if (current.status() == target) {
      return summaryOf(current);
    }

    store.updateStatus(agentId, target);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("previousStatus", current.status().name());
    payload.put("status", target.name());
    ledgerService.append(agentId, null, LedgerEventType.AGENT_STATUS_CHANGED, decidedBy, payload);

    log.info(
        "Changed agent status: agentId={} from={} to={} actor={}",
        agentId,
        current.status(),
        target,
        decidedBy);
    return summaryOf(
        new Agent(current.id(), current.name(), target, current.policy(), current.createdAt()));
  }

  private AgentSummary summaryOf(Agent agent) {
    AgentBudget budget =
        budgetReader
            .totalsFor(BudgetPeriod.current(clock))
            .getOrDefault(agent.id(), AgentBudget.empty());
    return new AgentSummary(agent, budget.spentMinor(), budget.reservedMinor());
  }

  private static String requireActor(String actor) {
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("actor is required");
    }
    String trimmed = actor.trim();
    return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
  }

  private static List<String> sorted(Set<String> payees) {
    return payees.stream().sorted().toList();
  }
}
