package com.leash.payment.api;

import jakarta.validation.constraints.Size;

/** Optional human note attached to an approve or reject decision. */
public record AdminApprovalDecisionRequest(@Size(max = 500) String note) {}
