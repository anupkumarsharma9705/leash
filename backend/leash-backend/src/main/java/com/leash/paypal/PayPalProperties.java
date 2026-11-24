package com.leash.paypal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * PayPal sandbox configuration.
 *
 * <p>Credentials are optional at boot so the dashboard can run without them; the OAuth adapter
 * rejects requests with a clear error when they are missing.
 */
@Validated
@ConfigurationProperties(prefix = "paypal")
public record PayPalProperties(
    String clientId,
    String clientSecret,
    @NotBlank String baseUrl,
    String webhookId,
    @DefaultValue("false") boolean allowLive,
    @NotNull @DefaultValue("3s") Duration connectTimeout,
    @NotNull @DefaultValue("10s") Duration readTimeout) {}
