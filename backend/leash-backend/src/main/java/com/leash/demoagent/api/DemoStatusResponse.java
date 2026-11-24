package com.leash.demoagent.api;

import com.leash.demoagent.DemoMode;
import java.util.List;
import java.util.UUID;

/**
 * Tells the console whether the demo runner exists and is ready.
 *
 * <p>The plaintext agent key is intentionally absent. The UI only needs the agent identity to label
 * the runner and to select the right agent in the header.
 */
public record DemoStatusResponse(
    boolean enabled,
    boolean ready,
    UUID agentId,
    String agentName,
    int invoiceCount,
    boolean llmConfigured,
    List<DemoMode> modes) {}
