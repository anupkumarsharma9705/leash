package com.leash.payment.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The {@code POST /api/v1/payments} request body. */
public record CreatePaymentRequest(
    @NotBlank @Email @Size(max = 254) String payeeEmail,
    @NotNull @Positive Long amountMinor,
    @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three-letter code")
        String currency,
    @Size(max = 255) String memo) {}
