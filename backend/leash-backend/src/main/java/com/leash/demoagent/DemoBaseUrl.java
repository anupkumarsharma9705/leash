package com.leash.demoagent;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Resolves the base URL of the running Leash server.
 *
 * <p>The demo agent must use the public HTTP API, so it needs its own address. Listening for the
 * initialized web server keeps random test ports and the deployed {@code PORT} working without a
 * second configuration value.
 */
@Component
@Profile("demo")
public class DemoBaseUrl implements DemoLeashBaseUrl {

  private final AtomicReference<String> baseUrl = new AtomicReference<>("http://127.0.0.1:8080");

  @EventListener
  public void onWebServerReady(WebServerInitializedEvent event) {
    baseUrl.set("http://127.0.0.1:" + event.getWebServer().getPort());
  }

  @Override
  public String current() {
    return baseUrl.get();
  }
}
