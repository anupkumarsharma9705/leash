package com.leash.agent.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The {@code POST /api/v1/admin/agents} request body. */
public record AdminCreateAgentRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull @Min(0) Long weeklyLimitMinor,
    @NotNull @Min(0) Long autoApproveThresholdMinor,
    @NotNull @Size(max = 100) List<@NotBlank @Email @Size(max = 254) String> allowedPayees) {}
