package com.leash.demoagent.api;

import com.leash.demoagent.DemoAgentCredentials;
import com.leash.demoagent.DemoAgentIdentity;
import com.leash.demoagent.DemoAgentRunner;
import com.leash.demoagent.DemoInvoiceLoader;
import com.leash.demoagent.DemoMode;
import com.leash.demoagent.DemoRunResult;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-profile-only administration.
 *
 * <p>These routes sit under {@code /api/v1/admin/**}, so they use the admin security chain and CSRF
 * on the write. The controller does not exist outside the demo profile, which is how the console
 * hides the runner in every other environment.
 */
@RestController
@RequestMapping("/api/v1/admin/demo")
@Profile("demo")
class AdminDemoController {

  private final DemoAgentRunner runner;
  private final DemoAgentCredentials credentials;
  private final DemoInvoiceLoader invoices;

  AdminDemoController(
      DemoAgentRunner runner, DemoAgentCredentials credentials, DemoInvoiceLoader invoices) {
    this.runner = runner;
    this.credentials = credentials;
    this.invoices = invoices;
  }

  @GetMapping("/status")
  DemoStatusResponse status() {
    DemoAgentIdentity identity = credentials.current().orElse(null);
    UUID agentId = identity == null ? null : identity.agentId();
    String agentName = identity == null ? null : identity.agentName();
    return new DemoStatusResponse(
        true,
        identity != null,
        agentId,
        agentName,
        invoices.invoices().size(),
        false,
        List.of(DemoMode.SCRIPTED, DemoMode.LLM));
  }

  @PostMapping("/run")
  DemoRunResponse run(@RequestParam(defaultValue = "SCRIPTED") DemoMode mode) {
    DemoRunResult result = runner.run(mode);
    return DemoRunResponse.from(result);
  }
}
