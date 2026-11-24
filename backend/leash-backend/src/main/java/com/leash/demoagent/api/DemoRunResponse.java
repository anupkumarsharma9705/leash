package com.leash.demoagent.api;

import com.leash.demoagent.DemoBudget;
import com.leash.demoagent.DemoMode;
import com.leash.demoagent.DemoRunResult;
import com.leash.demoagent.DemoSceneResult;
import java.time.Instant;
import java.util.List;

/** The stable API shape of one scripted demo run. */
public record DemoRunResponse(
    DemoMode mode,
    Instant startedAt,
    Instant finishedAt,
    DemoBudget budgetBefore,
    DemoBudget budgetAfter,
    List<DemoSceneResult> scenes,
    String summary) {

  public static DemoRunResponse from(DemoRunResult result) {
    return new DemoRunResponse(
        result.mode(),
        result.startedAt(),
        result.finishedAt(),
        result.budgetBefore(),
        result.budgetAfter(),
        result.scenes(),
        result.summary());
  }
}
