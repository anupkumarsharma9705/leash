package com.leash.demoagent;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Holds the demo agent's in-memory credentials.
 *
 * <p>The seeder publishes them once at startup. Only the demo HTTP client reads the plaintext key;
 * the admin status endpoint exposes the agent id and name but never the key.
 */
@Component
@Profile("demo")
public class DemoAgentCredentials {

  private final AtomicReference<DemoAgentIdentity> current = new AtomicReference<>();

  void publish(DemoAgentIdentity identity) {
    current.set(identity);
  }

  public Optional<DemoAgentIdentity> current() {
    return Optional.ofNullable(current.get());
  }

  DemoAgentIdentity require() {
    DemoAgentIdentity identity = current.get();
    if (identity == null) {
      throw new DemoAgentNotReadyException("Demo agent credentials are not available yet");
    }
    return identity;
  }
}
