package com.leash.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public configuration endpoint for frontend.
 *
 * <p>Exposes non-sensitive runtime configuration such as the live-mode flag.
 */
@RestController
@RequestMapping("/api/v1/config")
class ConfigController {

  @Value("${LEASH_ALLOW_LIVE:false}")
  private boolean allowLive;

  @GetMapping
  ConfigResponse config() {
    return new ConfigResponse(allowLive);
  }

  record ConfigResponse(boolean allowLive) {}
}
