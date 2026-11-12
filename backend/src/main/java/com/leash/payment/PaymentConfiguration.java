package com.leash.payment;

import com.leash.payment.domain.PaymentStateMachine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the pure payment state machine and enables the scheduled reconciler. */
@Configuration
@EnableScheduling
class PaymentConfiguration {

  @Bean
  PaymentStateMachine paymentStateMachine() {
    return new PaymentStateMachine();
  }
}
