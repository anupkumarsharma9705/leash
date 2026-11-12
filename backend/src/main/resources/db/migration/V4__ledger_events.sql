-- Append-only business ledger.
--
-- This is the audit trail. Rows are never updated or deleted, so it deliberately has no foreign
-- keys: deleting an agent or payment must never erase or block its ledger history. The columns are
-- indexed for the dashboard timeline instead.

CREATE TABLE ledger_events (
    id BIGSERIAL PRIMARY KEY,
    agent_id UUID NOT NULL,
    payment_id UUID,
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ledger_events_event_type_not_blank CHECK (length(btrim(event_type)) > 0),
    CONSTRAINT ledger_events_actor_not_blank CHECK (length(btrim(actor)) > 0),
    CONSTRAINT ledger_events_payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX ledger_events_agent_created_idx ON ledger_events (agent_id, created_at DESC);
CREATE INDEX ledger_events_payment_idx ON ledger_events (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX ledger_events_created_idx ON ledger_events (created_at DESC);

CREATE OR REPLACE FUNCTION reject_ledger_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger_events is append-only; % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ledger_events_no_update
    BEFORE UPDATE ON ledger_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_event_mutation();

CREATE TRIGGER ledger_events_no_delete
    BEFORE DELETE ON ledger_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_event_mutation();

CREATE TRIGGER ledger_events_no_truncate
    BEFORE TRUNCATE ON ledger_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_ledger_event_mutation();
