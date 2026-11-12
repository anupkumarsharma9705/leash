package com.leash.paypal;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
class PayPalConfiguration {

  @Bean
  ApplicationRunner paypalSandboxGuard(PayPalProperties properties) {
    return args -> SandboxGuard.verify(properties.baseUrl(), properties.allowLive());
  }

  @Bean
  RestClient payPalRestClient(PayPalProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder()
        .requestFactory(requestFactory)
        .baseUrl(properties.baseUrl())
        .build();
  }

  @Bean
  PayPalOAuthClient payPalOAuthClient(
      RestClient payPalRestClient, PayPalProperties properties, Clock clock) {
    return new PayPalOAuthClient(payPalRestClient, properties, clock);
  }

  @Bean
  PayPalPayoutsClient payPalPayoutsClient(
      RestClient payPalRestClient, PayPalOAuthClient oauthClient) {
    return new PayPalPayoutsAdapter(payPalRestClient, oauthClient);
  }

  @Bean
  PayPalWebhookVerifier payPalWebhookVerifier(
      RestClient payPalRestClient,
      PayPalOAuthClient oauthClient,
      PayPalProperties properties,
      ObjectMapper objectMapper) {
    return new PayPalWebhookVerifierAdapter(
        payPalRestClient, oauthClient, properties, objectMapper);
  }
}
