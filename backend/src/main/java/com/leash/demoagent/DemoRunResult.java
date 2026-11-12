package com.leash.demoagent;

import java.time.Instant;
import java.util.List;

/** The agent's complete report for one run, shown in the admin console. */
public record DemoRunResult(
    DemoMode mode,
    Instant startedAt,
    Instant finishedAt,
    DemoBudget budgetBefore,
    DemoBudget budgetAfter,
    List<DemoSceneResult> scenes,
    String summary) {

  public DemoRunResult {
    scenes = scenes == null ? List.of() : List.copyOf(scenes);
  }
}
