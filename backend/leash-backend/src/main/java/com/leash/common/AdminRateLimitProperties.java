package com.leash.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Toggles the admin brute-force limiter.
 *
 * <p>The limiter is on in every real deployment. Tests disable it explicitly so a suite full of
 * intentional 401s cannot poison the per-IP counter; production never keys the decision off the
 * password hash.
 */
@ConfigurationProperties("leash.admin.rate-limit")
public record AdminRateLimitProperties(@DefaultValue("true") boolean enabled) {}
