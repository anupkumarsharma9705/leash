package com.leash.demoagent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Demo-profile settings.
 *
 * <p>The seeder uses these values to create (or update) the demo agent, and the HTTP client uses
 * the timeouts. They exist only for the demo profile; production profiles never expose the demo
 * routes.
 */
@Validated
@ConfigurationProperties("leash.demo")
public record DemoProperties(
    @DefaultValue("Invoice Agent") @NotBlank String agentName,
    @DefaultValue("200000") @PositiveOrZero long weeklyLimitMinor,
    @DefaultValue("5000") @PositiveOrZero long autoApproveThresholdMinor,
    @DefaultValue("alice@example.com") @NotEmpty List<@NotBlank String> allowedPayees,
    @DefaultValue("5s") @NotNull Duration connectTimeout,
    @DefaultValue("15s") @NotNull Duration readTimeout) {}
