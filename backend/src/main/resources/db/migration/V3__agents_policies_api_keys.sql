-- Agents, their spending policies, and hashed API keys.
-- Plaintext API keys are never stored: only a SHA-256 hash and an 8-character identification prefix.

CREATE TABLE agents (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT agents_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT agents_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE policies (
    agent_id UUID PRIMARY KEY REFERENCES agents (id) ON DELETE CASCADE,
    weekly_limit_minor BIGINT NOT NULL,
    auto_approve_threshold_minor BIGINT NOT NULL,
    currency TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT policies_weekly_limit_non_negative CHECK (weekly_limit_minor >= 0),
    CONSTRAINT policies_threshold_non_negative CHECK (auto_approve_threshold_minor >= 0),
    CONSTRAINT policies_currency_usd CHECK (currency = 'USD')
);

CREATE TABLE policy_payees (
    agent_id UUID NOT NULL REFERENCES policies (agent_id) ON DELETE CASCADE,
    email_normalized TEXT NOT NULL,
    PRIMARY KEY (agent_id, email_normalized),
    CONSTRAINT policy_payees_email_normalized CHECK (
        length(email_normalized) > 0
        AND email_normalized = lower(btrim(email_normalized))
    )
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    key_hash TEXT NOT NULL UNIQUE,
    prefix TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT api_keys_hash_sha256_hex CHECK (length(key_hash) = 64),
    CONSTRAINT api_keys_prefix_length CHECK (length(prefix) = 8)
);

CREATE INDEX api_keys_prefix_idx ON api_keys (prefix);
CREATE INDEX api_keys_agent_idx ON api_keys (agent_id);
