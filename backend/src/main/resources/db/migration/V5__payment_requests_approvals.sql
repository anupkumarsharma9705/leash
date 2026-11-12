-- Payment requests and approvals.
--
-- Budget semantics:
--   spent    = APPROVED + SUBMITTED + SUCCEEDED  (committed money)
--   reserved = PENDING_APPROVAL                  (waiting for a human)
-- DENIED, REJECTED, and FAILED release their amount.

CREATE TABLE payment_requests (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents (id),
    idempotency_key TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    payee_email TEXT NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency TEXT NOT NULL,
    memo TEXT NOT NULL,
    state TEXT NOT NULL,
    decision_outcome TEXT NOT NULL,
    decision_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    paypal_batch_id TEXT,
    raw_status TEXT,
    budget_limit_minor BIGINT NOT NULL,
    budget_spent_minor BIGINT NOT NULL,
    budget_reserved_minor BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_requests_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payment_requests_currency_length CHECK (length(currency) = 3),
    CONSTRAINT payment_requests_memo_length CHECK (length(memo) <= 255),
    CONSTRAINT payment_requests_payee_length CHECK (length(btrim(payee_email)) > 0),
    CONSTRAINT payment_requests_idempotency_key_length CHECK (
        length(idempotency_key) BETWEEN 1 AND 128
    ),
    CONSTRAINT payment_requests_fingerprint_sha256 CHECK (length(request_fingerprint) = 64),
    CONSTRAINT payment_requests_state_valid CHECK (
        state IN (
            'PENDING_APPROVAL',
            'APPROVED',
            'SUBMITTED',
            'SUCCEEDED',
            'FAILED',
            'REJECTED',
            'DENIED'
        )
    ),
    CONSTRAINT payment_requests_outcome_valid CHECK (
        decision_outcome IN ('APPROVE', 'ESCALATE', 'DENY')
    ),
    CONSTRAINT payment_requests_reasons_is_array CHECK (
        jsonb_typeof(decision_reasons) = 'array'
    ),
    CONSTRAINT payment_requests_deny_has_reason CHECK (
        decision_outcome <> 'DENY' OR jsonb_array_length(decision_reasons) > 0
    ),
    CONSTRAINT payment_requests_non_deny_has_no_reason CHECK (
        decision_outcome = 'DENY' OR jsonb_array_length(decision_reasons) = 0
    ),
    CONSTRAINT payment_requests_deny_is_denied CHECK (
        decision_outcome <> 'DENY' OR state = 'DENIED'
    ),
    CONSTRAINT payment_requests_budget_non_negative CHECK (
        budget_limit_minor >= 0
        AND budget_spent_minor >= 0
        AND budget_reserved_minor >= 0
    ),
    CONSTRAINT payment_requests_agent_idempotency_unique UNIQUE (agent_id, idempotency_key)
);

CREATE INDEX payment_requests_agent_created_idx
    ON payment_requests (agent_id, created_at DESC);
CREATE INDEX payment_requests_state_created_idx
    ON payment_requests (state, created_at);
CREATE INDEX payment_requests_batch_idx
    ON payment_requests (paypal_batch_id)
    WHERE paypal_batch_id IS NOT NULL;

CREATE TABLE approvals (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL UNIQUE REFERENCES payment_requests (id) ON DELETE RESTRICT,
    status TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by TEXT,
    note TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT approvals_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT approvals_pending_has_no_decision CHECK (
        status <> 'PENDING' OR (decided_at IS NULL AND decided_by IS NULL)
    ),
    CONSTRAINT approvals_decided_has_decision CHECK (
        status = 'PENDING' OR (decided_at IS NOT NULL AND decided_by IS NOT NULL)
    ),
    CONSTRAINT approvals_note_length CHECK (note IS NULL OR length(note) <= 500)
);

CREATE INDEX approvals_status_requested_idx ON approvals (status, requested_at);
CREATE INDEX approvals_payment_idx ON approvals (payment_id);
