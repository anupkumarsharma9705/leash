package com.leash.demoagent;

/** A live budget snapshot returned by the agent API. */
public record DemoBudget(
    long limitMinor, long spentMinor, long reservedMinor, long remainingMinor) {}
