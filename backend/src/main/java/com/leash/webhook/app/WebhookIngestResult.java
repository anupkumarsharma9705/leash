package com.leash.webhook.app;

/** The outcome of receiving one webhook delivery. */
public enum WebhookIngestResult {
  /** First delivery, applied successfully. */
  PROCESSED,

  /** A retry of a stored event that had not yet been applied. */
  REPLAYED,

  /** The event id was already applied; no work was done. */
  DUPLICATE,

  /** The event was stored, but no payment applier is registered yet. */
  STORED
}
