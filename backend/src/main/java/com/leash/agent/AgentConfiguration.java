package com.leash.agent;

import com.leash.agent.domain.ApiKeyGenerator;
import com.leash.agent.domain.ApiKeyHasher;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the agent domain's key primitives. */
@Configuration
class AgentConfiguration {

  @Bean
  ApiKeyGenerator apiKeyGenerator() {
    return new ApiKeyGenerator(new SecureRandom());
  }

  @Bean
  ApiKeyHasher apiKeyHasher() {
    return new ApiKeyHasher();
  }
}
