-- Webhook ingestion is separate from payment application: the raw event is stored first so PayPal can
-- receive a fast acknowledgement even if application logic is briefly unavailable.
CREATE TABLE webhook_events (
    event_id TEXT PRIMARY KEY,
    event_type TEXT NOT NULL,
    raw_body JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT webhook_events_event_id_not_blank CHECK (length(event_id) > 0),
    CONSTRAINT webhook_events_event_type_not_blank CHECK (length(event_type) > 0)
);

-- The reconciler will scan unprocessed events; processed rows stay out of the index.
CREATE INDEX webhook_events_unprocessed_idx
    ON webhook_events (received_at)
    WHERE processed_at IS NULL;
