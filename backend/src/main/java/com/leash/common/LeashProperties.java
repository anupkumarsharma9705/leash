package com.leash.common;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Platform-wide limits. Validated at startup so bad configuration fails fast. */
@Validated
@ConfigurationProperties(prefix = "leash")
public record LeashProperties(@DefaultValue("100000") @Min(1) long maxPaymentMinor) {}
