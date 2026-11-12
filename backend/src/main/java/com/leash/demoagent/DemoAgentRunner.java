package com.leash.demoagent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Runs the deterministic demo scene set.
 *
 * <p>Each invoice goes through the public HTTP API. A failure on one invoice is recorded and the
 * run continues, so the summary always covers every scene. Two concurrent runs are serialized;
 * invoice idempotency keys would make them safe anyway, but serial execution keeps the report
 * readable.
 */
@Service
@Profile("demo")
public class DemoAgentRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoAgentRunner.class);

  private final DemoLeashClient client;
  private final DemoInvoiceLoader invoiceLoader;
  private final Clock clock;
  private final ReentrantLock runLock = new ReentrantLock();

  public DemoAgentRunner(DemoLeashClient client, DemoInvoiceLoader invoiceLoader, Clock clock) {
    this.client = client;
    this.invoiceLoader = invoiceLoader;
    this.clock = clock;
  }

  public DemoRunResult run(DemoMode mode) {
    if (mode == null || mode == DemoMode.SCRIPTED) {
      return runScripted();
    }
    throw new DemoLlmNotConfiguredException();
  }

  private DemoRunResult runScripted() {
    runLock.lock();
    try {
      Instant startedAt = clock.instant();
      DemoBudget budgetBefore = client.checkBudget();

      List<DemoSceneResult> scenes = new ArrayList<>();
      for (DemoInvoice invoice : invoiceLoader.invoices()) {
        try {
          scenes.add(DemoSceneResult.from(invoice, client.payInvoice(invoice)));
        } catch (DemoClientException exception) {
          scenes.add(DemoSceneResult.from(invoice, exception));
        }
      }

      DemoBudget budgetAfter = readBudgetAfterRun();
      String summary = buildSummary(scenes, budgetAfter);
      DemoRunResult result =
          new DemoRunResult(
              DemoMode.SCRIPTED,
              startedAt,
              clock.instant(),
              budgetBefore,
              budgetAfter,
              scenes,
              summary);
      log.info("Demo run finished: mode=SCRIPTED scenes={} summary={}", scenes.size(), summary);
      return result;
    } finally {
      runLock.unlock();
    }
  }

  private DemoBudget readBudgetAfterRun() {
    try {
      return client.checkBudget();
    } catch (DemoClientException exception) {
      log.warn("Could not read the budget after the demo run: code={}", exception.code());
      return null;
    }
  }

  private static String buildSummary(List<DemoSceneResult> scenes, DemoBudget budgetAfter) {
    int approved = 0;
    int escalated = 0;
    int denied = 0;
    int failed = 0;
    int replayed = 0;
    List<String> denialCodes = new ArrayList<>();

    for (DemoSceneResult scene : scenes) {
      if (!scene.decisionReceived()) {
        failed++;
        continue;
      }
      if (scene.replayed()) {
        replayed++;
      }
      switch (scene.outcome() == null ? "" : scene.outcome()) {
        case "APPROVE" -> approved++;
        case "ESCALATE" -> escalated++;
        case "DENY" -> {
          denied++;
          scene.reasons().stream()
              .map(DemoPayment.DemoReason::code)
              .filter(code -> code != null && !code.isBlank())
              .forEach(denialCodes::add);
        }
        default -> failed++;
      }
    }

    StringBuilder summary = new StringBuilder();
    summary
        .append("Processed ")
        .append(scenes.size())
        .append(scenes.size() == 1 ? " invoice" : " invoices")
        .append(": ")
        .append(approved)
        .append(" approved, ")
        .append(escalated)
        .append(" escalated, ")
        .append(denied)
        .append(" denied");
    if (failed > 0) {
      summary.append(", ").append(failed).append(" failed");
    }
    summary.append('.');
    if (!denialCodes.isEmpty()) {
      summary.append(" Denied because ").append(String.join(", ", denialCodes)).append('.');
    }
    if (replayed > 0) {
      summary
          .append(' ')
          .append(replayed)
          .append(replayed == 1 ? " invoice was" : " invoices were")
          .append(" an idempotent replay; no second payout was created.");
    }
    if (budgetAfter != null) {
      summary
          .append(" Remaining weekly budget: ")
          .append(money(budgetAfter.remainingMinor()))
          .append('.');
    }
    return summary.toString();
  }

  /** Formats integer minor units without floating-point arithmetic. */
  static String money(long minorUnits) {
    long absolute = Math.abs(minorUnits);
    String whole = String.format(Locale.ROOT, "%,d", absolute / 100);
    String cents = String.format(Locale.ROOT, "%02d", absolute % 100);
    return (minorUnits < 0 ? "-$" : "$") + whole + "." + cents;
  }
}
