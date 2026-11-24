package com.leash.common;

import com.leash.policy.PolicyEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure policy engine with the platform hard cap. */
@Configuration
class PolicyConfiguration {

  @Bean
  PolicyEngine policyEngine(LeashProperties properties) {
    return new PolicyEngine(properties.maxPaymentMinor());
  }
}
