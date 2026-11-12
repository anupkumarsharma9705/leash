# AGENTS.md: Leash — scope, stack, and domain rules

> Read PROJECT_RULES.md first. It defines workflow and approval rules; this file defines scope, stack and domain rules.

> **Status: reconstructed draft, 2026-11-10.** The original AGENTS.md was not present in the repository. This file was rebuilt from the references in `PROJECT_RULES.md` and `docs/IMPLEMENTATION_PLAN.md` so implementation can proceed. The owner must approve it before Phase 1. Until then, treat §8 (policy engine) and §16 (API contract) as proposed, not frozen.

---

## 1. What Leash is

Leash is a spending-control gateway between an AI agent and PayPal.

An owner registers an agent and a policy (weekly budget, allowed payees, auto-approve threshold). The agent calls Leash over its public HTTP API with its own API key. Leash evaluates policy and returns one of three decisions:

| Decision | Meaning |
|---|---|
| **APPROVE** | Within policy and under the auto-approve threshold. Leash submits a PayPal sandbox Payout. |
| **DENY** | A hard rule failed. No money moves. The reason is returned and ledgered. |
| **ESCALATE** | Within hard rules but above the auto-approve threshold. A human approves or rejects, then Leash proceeds. |

One-sentence pitch for the demo: **"Companies won't give an AI agent an open wallet."**

### 1.1 Trust boundary (non-negotiable)

1. The LLM is **untrusted input**. Its output can never bypass policy, change a decision, or read credentials.
2. The LLM never holds PayPal credentials and never calls PayPal directly. Only the PayPal adapter does.
3. `DENY` and `ESCALATE` are **final outcomes to report**, not obstacles to work around. The system prompt forbids splitting a payment to evade limits or budgets.
4. Invoice text, memos, and all model output are treated as untrusted data end to end.

---

## 2. Glossary

| Term | Meaning |
|---|---|
| **Agent** | A registered AI actor with a name, status, API key, and one policy. |
| **Policy** | Weekly budget, allowed payee emails, auto-approve threshold, currency (USD only). |
| **Payment request** | One request from an agent to pay a payee. Owned by one agent. |
| **Decision** | Result of evaluating policy: `APPROVE`, `ESCALATE`, or `DENY`, plus reason codes. |
| **Approval** | The human decision record attached to an escalated payment. |
| **Ledger event** | Append-only business fact (request, decision, submission, status change, policy change). |
| **Budget period** | UTC calendar week, Monday 00:00:00 UTC to the next Monday 00:00:00 UTC. |

---

## 3. Scope

### 3.1 In scope (MVP)

1. Agent registry: create (API key shown once), list with spend vs budget, suspend, resume, rotate key.
2. Policy engine as pure Java, fixed evaluation order, with unit tests.
3. `POST /api/v1/payments` gateway with required idempotency key, budget locking, pure policy evaluation, and a committed-before-PayPal rule.
4. PayPal sandbox **Payouts** submission behind a port, with OAuth token caching, bounded retries, and status lookup.
5. PayPal webhook receiver with raw-body signature verification and event dedupe.
6. Append-only ledger enforced by a database trigger.
7. Payment state machine as the only place transitions happen.
8. Approval workflow: approve triggers submission, reject releases reserved budget, both race-safe.
9. Scheduled reconciler: retry stuck submissions, poll stale submitted payments.
10. Demo agent in `demo` profile, two modes: `SCRIPTED` (reliable) and `LLM` (tool calling). It calls Leash only over the public HTTP API with its own agent key.
11. Admin console (React + TypeScript + AG Grid Community): ledger grid, payment timeline drawer, approvals panel, budget bars, kill switch, policy editor, demo runner.
12. Three demo scenes: auto-approve ($12), escalate ($150), deny ($900 to an unknown payee).
13. Docker image + Render deploy for the hosted demo; full run-from-source instructions.
14. README, architecture doc, progress log, ADRs.

### 3.2 Out of scope (do not build without explicit approval)

- Real money, live PayPal endpoints, or any non-sandbox credentials.
- Payment providers other than PayPal.
- PayPal Checkout / Orders / Invoicing as money-movement rails in the MVP (Payouts is the rail).
- Agent marketplace, discovery, negotiation, or agent-to-agent settlements.
- Multi-tenant organizations, teams, SSO, MFA.
- Currency other than USD.
- Mobile app.
- Notifications beyond the dashboard approval UI (Zapier is an optional stretch, cut first).
- Claims that the product is production-ready.

**Cut order when behind** (apply in this order; never cut security or data integrity):

1. Agent-to-agent stretch scene.
2. Zapier approval notifications (dashboard approvals remain).
3. Channel3 real-time feed.
4. Velocity limit and the prompt-injection demo invoice (Gate G2).
5. Policy-editor UI and live push updates — keep 3-second polling (Gate G3).
6. Policy editor entirely (API-only policy edits).

**Never cut:** a working PayPal sandbox payment, verified webhooks, the ledger dashboard, idempotency and budget integrity, and the demo video.

---

## 4. Fixed stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot, current stable GA generated by **start.spring.io** (agents MUST NOT hand-write `pom.xml`). Record the actual version in `docs/PROGRESS.md` after generation. |
| Build | Maven with Maven Wrapper (`./mvnw`) |
| Database | PostgreSQL 16 + Flyway migrations |
| Backend libs | Spring Web, Validation, Data JPA, Security, Actuator, springdoc-openapi |
| Test libs | JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock |
| Formatting | Spotless with google-java-format, enforced in `verify` |
| Runtime LLM | Spring AI (current 2.x line) behind the `AgentModelClient` port |
| Frontend | Vite + React + TypeScript (`strict`), TanStack Query, AG Grid Community, plain CSS variables |
| Hosting | Render: one Docker web service + one PostgreSQL (paid always-on recommended through judging) |

**Banned:** Lombok, field injection, `double`/`float` for money, exposing JPA entities from controllers, secrets in code or git.

**Fallback (needs an ADR and owner approval):** if Spring AI tool calling fails against the installed version after a 60-minute time-box (Gate L), use LangChain4j, or a thin client for the provider's OpenAI-compatible tool-calling API, behind the same `AgentModelClient` port.

**Optional sponsor stretch (time-boxed, cut order applies):** APIMatic SDK generation from the OpenAPI spec; Zapier approval notifications; Channel3 real-time feed.

---

## 5. Repository layout

The repository root is this folder. Base package `com.leash`.

```
.
├── AGENTS.md
├── PROJECT_RULES.md
├── README.md
├── LICENSE                      # MIT, added by owner on GitHub
├── .env.example
├── .gitignore
├── docker-compose.yml           # local PostgreSQL 16 only
├── render.yaml
├── docs/
│   ├── IMPLEMENTATION_PLAN.md
│   ├── PROGRESS.md
│   ├── architecture.md
│   ├── paypal-sandbox-setup.md
│   └── decisions/               # ADRs (001-payouts-spike.md, ...)
├── .github/workflows/ci.yml
├── backend/
│   ├── pom.xml                  # generated by start.spring.io
│   ├── mvnw, .mvn/
│   └── src/
│       ├── main/java/com/leash/
│       │   ├── LeashApplication.java
│       │   ├── common/          # error format, security config, properties, Clock
│       │   ├── agent/           # api → app → domain → infra
│       │   ├── policy/          # pure Java, no Spring imports
│       │   ├── payment/         # gateway, state machine, reconciler
│       │   ├── approval/
│       │   ├── ledger/
│       │   ├── paypal/          # ports + adapters
│       │   └── demoagent/       # demo profile only
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── db/migration/
│       │   └── demo/invoices.json
│       └── test/java/com/leash/
└── frontend/
    ├── package.json, vite.config.ts, tsconfig.json
    └── src/
        ├── api/                 # client.ts, types.ts — all HTTP here
        ├── components/          # DataTable, StatusBadge, BudgetBar, ConfirmDialog
        └── features/            # ledger, approvals, agents, demo
```

Feature packages are lower-case singular. Inside a feature: `api` (controllers, DTO records) → `app` (services, transactions) → `domain` (entities, rules) → `infra` (repositories, adapters). Dependencies point inward. Features talk through service interfaces, never another feature's repositories.

---

## 6. Architecture and ports

One deployable modular monolith. Frontend talks only HTTP.

```
Agent (scripted or LLM)
   │  Authorization: Bearer lsh_...
   ▼
POST /api/v1/payments
   │
   ├── AgentApiKeyFilter ──► hashed key lookup (constant time)
   ├── PaymentService
   │      ├── TX-A: lock agent row, replay check, load budget,
   │      │        pure PolicyEngine, persist request + approval + ledger
   │      │        COMMIT
   │      └── PayPalPayoutsClient.submit()   (outside any transaction)
   │             └── TX-B: store ids, state SUBMITTED
   ▼
PayPal sandbox Payouts  ──webhook──►  POST /webhooks/paypal
                                        verify signature
                                        dedupe by event id
                                        PaymentStateMachine
                                        ledger event
```

Ports (interfaces at integration boundaries):

| Port | Production adapter | Test fake |
|---|---|---|
| `PayPalPayoutsClient` | `PayPalPayoutsAdapter` (OAuth + REST) | `FakePayPalPayoutsClient` |
| `AgentModelClient` | `SpringAiAgentModelClient` | `FakeAgentModelClient` |

Inject `java.time.Clock`. Never call `Instant.now()` in business code. Configuration lives in validated `@ConfigurationProperties` classes (`PayPalProperties`, `LlmProperties`, `LeashProperties`, `AdminProperties`), not scattered `@Value`.

---

## 7. Money and payment state

### 7.1 Money

- Type: `Money(long amountMinor, Currency currency)` — minor units end to end.
- Supported currency: `USD` only.
- Amount must be positive and must not exceed the hard cap (default `100000` minor units = $1,000.00; configurable via `LEASH_MAX_PAYMENT_MINOR`).
- PayPal decimal string conversion happens **only at the adapter boundary**: `BigDecimal.valueOf(amountMinor).movePointLeft(2).toPlainString()` → `"12.00"`, `"0.01"`.
- No `double`, no `float`, no `BigDecimal` in the domain model.
- Formatting for display: minor units ÷ 100 with two decimals, currency-labelled.

### 7.2 PaymentStateMachine

Pure Java, no Spring/JPA imports. The only place transitions happen. Illegal transition → `IllegalStateTransitionException`.

States: `PENDING_APPROVAL`, `APPROVED`, `SUBMITTED`, `SUCCEEDED`, `FAILED`, `REJECTED`, `DENIED`.

| From | Allowed to |
|---|---|
| `PENDING_APPROVAL` | `APPROVED`, `REJECTED` |
| `APPROVED` | `SUBMITTED`, `FAILED` |
| `SUBMITTED` | `SUCCEEDED`, `FAILED` |
| `SUCCEEDED` | — terminal |
| `FAILED` | — terminal |
| `REJECTED` | — terminal |
| `DENIED` | — terminal |

Rules:

- Terminal states never move, even for out-of-order webhooks or reconciler retries.
- A webhook may only move `SUBMITTED` forward.
- The reconciler only acts on `APPROVED` (retry submit) and `SUBMITTED` (poll status).
- Every transition writes a ledger event in the same transaction.

---

## 8. Policy engine

Pure Java package `policy`. No Spring, JPA, or I/O imports. `PolicyDecision evaluate(PolicyContext context)`.

`PolicyContext` carries: agent id and status, policy (weekly limit, allowed payees, auto-approve threshold), amount and currency, normalized payee email, spent this period, reserved by pending approvals, and the `Clock`.

**Fixed evaluation order** (collect **all** hard-rule violations; the primary reason is the first violated rule in this order):

| # | Rule | Reason code |
|---|---|---|
| 1 | Agent is `ACTIVE` | `AGENT_SUSPENDED` |
| 2 | Currency is supported (USD) | `CURRENCY_NOT_SUPPORTED` |
| 3 | Amount is positive | `INVALID_AMOUNT` |
| 4 | Amount ≤ hard cap | `AMOUNT_EXCEEDS_CAP` |
| 5 | Payee is on the normalized allow-list | `PAYEE_NOT_ALLOWED` |
| 6 | `spent + reserved + amount ≤ weekly limit` | `BUDGET_EXCEEDED` |

Outcome:

- Any hard violation → `DENY`, with all reason codes (`Reasons` is ordered, primary first).
- Otherwise `amount ≤ autoApproveThreshold` → `APPROVE`.
- Otherwise → `ESCALATE`.

Budget rules:

- Period is the **UTC calendar week**, Monday 00:00:00 UTC inclusive to next Monday 00:00:00 UTC exclusive.
- **Pending approvals reserve budget.** They count toward `committed` until approved (becomes spent) or rejected (released).
- Emails are trimmed and lower-cased before allow-list comparison.

Tests: parameterized per rule; boundaries (`amount == limit`, `limit + 1`); multi-violation ordering; UTC midnight and Monday rollover; auto-approve threshold boundary. Target **≥ 90% line coverage** on the `policy` package.

---

## 9. Payment gateway

### 9.1 `POST /api/v1/payments` — exact sequence

1. Authenticate the agent from its API key.
2. Validate the body; require the `Idempotency-Key` header.
3. Compute `fingerprint = SHA-256` of the canonical request body (fixed field order: `payeeEmail`, `amountMinor`, `currency`, `memo`).
4. **TX-A** (short, no network):
   - Lock the agent row `PESSIMISTIC_WRITE`.
   - Check `(agent, idempotencyKey)`:
     - same fingerprint → return the stored response with header `Idempotent-Replayed: true`;
     - different body → `422 IDEMPOTENCY_KEY_REUSED`.
   - Load budget aggregates **inside the lock** (spent + reserved).
   - Call the pure `PolicyEngine`.
   - Persist the payment request, an approval row if `ESCALATE`, and ledger events.
   - **COMMIT.**
5. **Only then** submit to PayPal — never inside a transaction that holds locks.
6. **TX-B**: store PayPal ids and set `SUBMITTED`.

A `DENY` is HTTP `201` with `decision.outcome = "DENY"`; the request is persisted with state `DENIED` and ledgered with its reasons.

### 9.2 Idempotency invariants

- Unique constraint on `(agent_id, idempotency_key)`.
- Replay returns an identical body and the same `paymentId`.
- Same key, different body → `422`, code `IDEMPOTENCY_KEY_REUSED`.
- Two concurrent same-key requests → exactly one payment and exactly one PayPal call.
- Use `@Version` on `payment_requests`.

### 9.3 Concurrency invariants

- The `PESSIMISTIC_WRITE` agent lock serializes budget checks for one agent.
- N threads racing for the last budget dollars never exceed the weekly limit (integration test with Testcontainers).
- A crash between TX-A and PayPal submission leaves the row `APPROVED`; the reconciler finishes it.

### 9.4 Reconciler

Scheduled job (default every 30 s, configurable):

- `APPROVED` older than 60 s → retry submission (safe via `PayPal-Request-Id`).
- `SUBMITTED` older than 10 minutes → poll PayPal status.
- Every repair writes a ledger event.

---

## 10. PayPal integration

- **Sandbox only.** Startup guard: refuse to boot if `PAYPAL_BASE_URL` is not a sandbox host unless `LEASH_ALLOW_LIVE=true`. The guard is tested.
- **OAuth:** client-credentials flow with Spring `RestClient`; connect timeout 3 s, read timeout 10 s; in-memory thread-safe token cache refreshed at `expires_in − 60 s`; one refresh-and-retry on `401`. Never log tokens or secrets.
- **Payouts:** behind `PayPalPayoutsClient`:
  - `recipient_type = EMAIL`
  - `sender_batch_id = "leash-<paymentId>"`
  - `sender_item_id = "<paymentId>"`
  - header `PayPal-Request-Id: <paymentId>`
  - amount as decimal string, currency `USD`
- **Retries:** exponential backoff, max 3 attempts, only on timeout / 5xx / 429, and only with an idempotency key.
- **Status lookup:** poll by payout batch id.
- **Status mapping:** all translation in `PayPalStatusMapper` with an exhaustive table test. Store the raw PayPal status string alongside the mapped state.
- **Errors:** log `PayPal-Debug-Id` on every error response. Never log the body if it can contain secrets.
- Read the **installed-version** PayPal docs before implementing. Do not guess field names or statuses.

---

## 11. Webhooks

`POST /webhooks/paypal` is unauthenticated but signature-verified.

1. Read the **raw body bytes** before any JSON parsing.
2. Verify through PayPal's `verify-webhook-signature` API using the transmission headers and `PAYPAL_WEBHOOK_ID`, embedding the **raw event JSON text verbatim**.
3. Unverified → `400`. Nothing is persisted.
4. Dedupe by PayPal event id (primary key on `webhook_events`). Duplicate → `200`, no-op.
5. Persist the raw event first; respond fast (`200`).
6. Apply the event through `PayPalStatusMapper` → `PaymentStateMachine`. Out-of-order events cannot move a terminal state backward.
7. Ledger event actor: `paypal-webhook`.

Tests: valid signature accepted; tampered body rejected; duplicate ignored; out-of-order event cannot regress a terminal state.

---

## 12. Approvals

- Escalated payment → `PENDING_APPROVAL` + `approvals` row (`status = PENDING`).
- **Approve:** re-check hard rules first (agent still active, payee still allowed, budget still available). If violated → reject with a ledger event. Otherwise state `APPROVED` and trigger the same submission sequence as §9.1 steps 5–6.
- **Reject:** state `REJECTED`; reserved budget is released.
- Both operations are idempotent and race-safe with `@Version`. Approve racing reject has exactly one winner.
- Record `decidedBy` and `note`.
- Tests: double approve is a no-op; approve-vs-reject race; re-check rejection; budget release on reject.

---

## 13. Ledger

- Table `ledger_events` is **append-only**. A Flyway-created database trigger rejects `UPDATE` and `DELETE`. No repository methods for either.
- Written in the caller's transaction, so a business change and its ledger event commit together.
- Event fields: `id`, `agentId`, `paymentId` (nullable), `eventType`, `actor`, `payload` (JSONB), `createdAt`.
- Event types include: `PAYMENT_REQUESTED`, `DECISION_MADE`, `APPROVAL_CREATED`, `APPROVAL_DECIDED`, `PAYPAL_SUBMITTED`, `PAYPAL_STATUS_CHANGED`, `RECONCILED`, `POLICY_CHANGED`, `AGENT_STATUS_CHANGED`, `API_KEY_ROTATED`.
- Payloads never contain secrets, tokens, or full API keys (redaction test).
- The dashboard timeline reads from this table; it is the audit trail.

---

## 14. Demo agent

Profile `demo` only.

- Calls Leash **only over the public HTTP API** with its own agent key. No direct service or repository calls.
- Tools:
  - `check_budget()`
  - `pay_invoice(invoiceId, payeeEmail, amountMinor, currency, memo)` — idempotency key `"invoice-<invoiceId>"`.
- `demo/invoices.json` contains: $12 to an allowed payee, $150 to an allowed payee, $900 to an unknown payee.
- `POST /api/v1/admin/demo/run?mode=SCRIPTED|LLM` runs the scene set and returns the agent's final summary.
- **SCRIPTED mode** is deterministic and is the safety net for the video.
- **LLM mode** uses Spring AI tool calling behind `AgentModelClient`: provider, model, key, and base URL from env; max 8 tool iterations; timeout; temperature 0. The system prompt states that `DENY` and `ESCALATE` are final outcomes to report, and that splitting payments to evade limits is forbidden.
- The application boots and **all tests pass with no LLM key present** (the client is mocked).
- Optional demo scene: an invoice whose memo contains injected instructions. The agent must report the denial and not obey the memo.

---

## 15. Security

1. **No hardcoded secrets.** Environment variables only. `.env` is git-ignored; `.env.example` holds placeholders.
2. **Agent API keys:** format `lsh_<32 random bytes base64url>` from `SecureRandom`. Store only the SHA-256 hash plus an 8-character prefix for identification. Compare in constant time (`MessageDigest.isEqual`). Plaintext is shown exactly once at creation or rotation.
3. **Agent auth:** stateless API-key filter for `/api/v1/payments/**` and `/api/v1/budget`.
4. **Admin auth:** session login with HTTP Basic or form login; credentials from env; BCrypt hash only; no default password. CSRF enabled with a cookie token repository (`X-XSRF-TOKEN`). Admin endpoints: `/api/v1/admin/**`.
5. **An agent key must never reach any admin endpoint.** This is an automated test.
6. Input validation at the boundary (Bean Validation, size limits, allow-lists). Parameterized queries only. Treat invoice text and LLM output as untrusted.
7. Never log passwords, tokens, API keys, `Authorization` headers, or personal data. A redaction test covers the log formatter.
8. Sandbox only. Never use live credentials. Do not describe the project as production-ready.
9. Secret scan (`gitleaks` or the grep in the rules) before the first push and before submission. If a secret leaks, rotate it immediately — deleting the commit is not enough.
10. Standard protections: XSS (no `dangerouslySetInnerHTML` with untrusted data), CSRF on session routes, no SSRF (never fetch user-supplied URLs), safe deserialization.

---

## 16. API contract

Canonical. JSON in camelCase, timestamps ISO-8601 UTC. All errors use RFC 7807 `ProblemDetail` with extensions `code`, `details`, `correlationId`.

### Agent endpoints (API key auth)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/payments` | Requires `Idempotency-Key`. `201` on success, `DENY` included. |
| `GET` | `/api/v1/payments/{id}` | Owning agent only; another agent gets `404`. |
| `GET` | `/api/v1/budget` | Current period spend, reserved, remaining. |

Payment request body:

```json
{
  "payeeEmail": "alice@example.com",
  "amountMinor": 1200,
  "currency": "USD",
  "memo": "Invoice inv-1"
}
```

Payment response (`201`):

```json
{
  "paymentId": "3f0c...",
  "state": "SUBMITTED",
  "decision": { "outcome": "APPROVE", "reasons": [] },
  "budget": { "limitMinor": 50000, "spentMinor": 1200, "reservedMinor": 0, "remainingMinor": 48800 }
}
```

Deny response (`201`):

```json
{
  "paymentId": "8a1d...",
  "state": "DENIED",
  "decision": {
    "outcome": "DENY",
    "reasons": [{ "code": "PAYEE_NOT_ALLOWED", "message": "Payee is not on the allow-list" }]
  },
  "budget": { "limitMinor": 50000, "spentMinor": 1200, "reservedMinor": 0, "remainingMinor": 48800 }
}
```

Idempotent replay: identical body plus header `Idempotent-Replayed: true`. Same key with a different body: `422`, `code = IDEMPOTENCY_KEY_REUSED`.

### Admin endpoints (session auth + CSRF on writes)

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/admin/agents` | Creates agent; returns plaintext key once. |
| `GET` | `/api/v1/admin/agents` | List with spend vs budget. |
| `PUT` | `/api/v1/admin/agents/{id}/policy` | Policy edit; writes a ledger event. |
| `POST` | `/api/v1/admin/agents/{id}/suspend` | Kill switch. |
| `POST` | `/api/v1/admin/agents/{id}/resume` | |
| `POST` | `/api/v1/admin/agents/{id}/rotate-key` | Plaintext key shown once. |
| `GET` | `/api/v1/admin/payments` | Paged; filters `status`, `agentId`, `from`, `to`; `size` max 100; no N+1. |
| `GET` | `/api/v1/admin/payments/{id}/events` | Ledger timeline. |
| `POST` | `/api/v1/admin/payments/{id}/refresh` | Manual PayPal status poll. |
| `GET` | `/api/v1/admin/approvals` | Pending approvals. |
| `POST` | `/api/v1/admin/approvals/{id}/approve` | Body: `{ "note": "..." }`. |
| `POST` | `/api/v1/admin/approvals/{id}/reject` | Body: `{ "note": "..." }`. |
| `POST` | `/api/v1/admin/demo/run` | Demo profile only; `?mode=SCRIPTED\|LLM`. |

### Webhook

| Method | Path | Notes |
|---|---|---|
| `POST` | `/webhooks/paypal` | Signature-verified; raw body; dedupe by event id. |

### Health

`GET /actuator/health` → `{"status":"UP"}`. Used by Render.

### Error format

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "amountMinor must be positive",
  "instance": "/api/v1/payments",
  "code": "VALIDATION_FAILED",
  "correlationId": "0b0f..."
}
```

Status codes: `201` create, `204` empty success, `400` validation, `401/403` auth, `404` not found, `409` conflict, `422` semantic error (including idempotency reuse), `429` throttling.

---

## 17. Database

PostgreSQL 16. Money as `BIGINT` minor units + explicit currency. All schema changes through Flyway; never edit an applied migration.

Tables:

| Table | Key columns / constraints |
|---|---|
| `agents` | `id` UUID PK, `name`, `status` (`ACTIVE`/`SUSPENDED`), `created_at`. |
| `policies` | `agent_id` PK/FK, `weekly_limit_minor`, `auto_approve_threshold_minor`, `currency`, `updated_at`. |
| `policy_payees` | `(agent_id, email_normalized)` PK, FK to `policies`. |
| `api_keys` | `id`, `agent_id` FK, `key_hash` UNIQUE, `prefix`, `created_at`, `last_used_at`, `revoked_at`. |
| `payment_requests` | `id` UUID PK, `agent_id` FK, `idempotency_key`, `request_fingerprint`, `payee_email`, `amount_minor`, `currency`, `memo`, `state`, `decision_outcome`, `decision_reasons` JSONB, `paypal_batch_id`, `raw_status`, `version`, `created_at`, `updated_at`. UNIQUE `(agent_id, idempotency_key)`. |
| `approvals` | `id`, `payment_id` UNIQUE FK, `status` (`PENDING`/`APPROVED`/`REJECTED`), `requested_at`, `decided_at`, `decided_by`, `note`, `version`. |
| `ledger_events` | `id` BIGSERIAL PK, `agent_id`, `payment_id` nullable, `event_type`, `actor`, `payload` JSONB, `created_at`. Append-only trigger. |
| `webhook_events` | `event_id` TEXT PK, `event_type`, `raw_body` JSONB, `received_at`, `processed_at`. |

Rules:

- Foreign key indexes and indexes for frequent filters/sorts (`payment_requests(agent_id, created_at)`, `(state, created_at)`).
- `NOT NULL` and `CHECK` constraints for invariants (`amount_minor > 0`, `weekly_limit_minor >= 0`).
- No derivable columns without justification and a consistency test.
- Destructive changes require owner approval.

---

## 18. Configuration

Profiles: `local`, `demo`, `test`. Fail-fast validated `@ConfigurationProperties`. Full env list lives in `.env.example`.

| Property group | Env vars |
|---|---|
| PayPal | `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_BASE_URL`, `PAYPAL_WEBHOOK_ID`, `LEASH_ALLOW_LIVE` |
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (JDBC URL composed in config, never the `postgres://` form) |
| Admin | `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH` (BCrypt only) |
| LLM | `LEASH_LLM_BASE_URL`, `LEASH_LLM_API_KEY`, `LEASH_LLM_MODEL` |
| App | `PORT`, `SPRING_PROFILES_ACTIVE`, `LEASH_MAX_PAYMENT_MINOR` |

`server.port=${PORT:8080}`, `server.forward-headers-strategy=framework` for Render.

---

## 19. Observability

- Actuator: health, info, metrics. Health is the Render check.
- One correlation ID per payment flow (the payment request id), present in logs and error responses.
- Log levels per PROJECT_RULES §H: INFO for business events, WARN for handled surprises, ERROR for attention, DEBUG off in production.
- Log once per failure, at the boundary that handles it. Never log secrets or PII.

---

## 20. Testing

- **Unit (no Spring context):** policy engine (every rule, every boundary), payment state machine (every allowed and forbidden transition, terminal immutability), money arithmetic and formatting, idempotency fingerprinting.
- **Integration (Testcontainers PostgreSQL + WireMock PayPal):** payment pipeline, approval flow, webhook processing, concurrent budget races, auth boundaries (agent key cannot reach admin), replay behavior, reconciler crash repair.
- **Frontend:** build + type-check in CI; component tests for the four states where practical.
- Existing tests must keep passing. Never delete, skip, or weaken a test to get green.
- Run `./mvnw -B verify` (plus `npm run build && npm test` once the frontend exists) after every significant change and before every commit. Paste the real output.

---

## 21. Frontend

- Vite + React + TypeScript `strict`. TanStack Query for data. AG Grid **Community**. No UI component library, no Redux.
- All HTTP in `src/api/` (`client.ts`, `types.ts`): base URL, CSRF header `X-XSRF-TOKEN`, ProblemDetail error mapping. Components call hooks; hooks call the API layer.
- Every data view implements four states: **loading, error, empty, success**.
- AG Grid ledger: columns time/agent/payee/amount/decision/reason/PayPal status/correlation id (hidden); sorting; per-column filters; quick filter; server-side pagination; `getRowId = paymentId`; `refetchInterval: 3000`. Row click opens the ledger timeline drawer.
- Money displayed from minor units; never floats.
- Approvals panel with confirmation dialog (payee + amount), buttons disabled while in flight. Budget bars (spent + reserved vs limit). Header agent selector and kill switch. Policy editor drawer. Demo runner visible only under the demo profile.
- Styling: CSS variables, light and dark, spacing scale, one accent color, visible focus, responsive, keyboard accessible. Restrained fintech console, not a default template.
- No hardcoded mock data in production paths. Demo data comes from the backend `demo` seeder and is labelled.
- Read the **installed AG Grid version docs** before writing grid code (module registration and theming change across majors).

---

## 22. Limits and performance

- Server-side maximum page size: 100.
- Outbound timeouts: PayPal connect 3 s / read 10 s; LLM explicit timeout.
- No N+1 queries on list endpoints; verify with the SQL log.
- One payout item per payment request.

---

## 23. Documentation

- `README.md`: see §24.
- `docs/architecture.md`: request flow and state machine, in text + Mermaid.
- `docs/PROGRESS.md`: done, assumptions, blockers, next slice. Updated every slice.
- `docs/decisions/NNN-*.md`: one ADR per non-obvious decision, including the Payouts sandbox spike, any LLM fallback, and any deviation from this file.

---

## 24. README requirements

1. What it is and why (the wallet problem).
2. Architecture diagram (Mermaid) and the request flow.
3. Run from source: prerequisites, `docker compose up`, env setup, `./mvnw spring-boot:run`, frontend commands.
4. Test credentials / sandbox login details for judges.
5. The exact LLM provider and model used.
6. Sandbox-only statement and "not production-ready" limits.
7. What is new since Oct 1, 2026 (hackathon rule).
8. Sponsor tools used (AG Grid, Render, ...) and how.
9. License (MIT).

---

## 25. Git

- Conventional Commits, small and focused.
- `main` stays green. Never force-push `main`.
- Never commit secrets, `.env`, `target/`, `node_modules/`.
- Do not revert or overwrite changes you did not make.
- No drive-by refactors or unrelated reformatting.
- **Freeze:** after Thursday Nov 12, 2026, 12:00 pm PT / 18:00 IST submission, no commits, README edits, or video changes until winners are announced.

---

## 26. Time budget

~20 working hours. Deadline: Fri Nov 13, 01:30 IST (Thu Nov 12, 12:00 pm PT). Submit by **Thu Nov 12, 18:00 IST**.

| Block (IST) | Hours | Phase |
|---|---|---|
| Tue 16:00–23:00 | 7 | Phase 0 (1h) + Phase 1 (5h) + Slice 2.1 (1h) |
| Wed 09:00–22:00 | 8 | Rest of Phase 2 (4h) + Phase 3 (4h) |
| Thu 10:00–18:00 | 5 | Phase 4 (2h) + Phase 5 (3h) |

Gates: **G1** real sandbox payout works (Tue ~21:00) · **G2** three scenes pass via curl (Wed ~13:00) · **G3** full flow clickable in browser (Wed ~22:00) · **G4** deployed on a public URL (Thu ~13:00) · **G5** submitted (Thu 18:00). If a gate fails, apply §3.2; do not push through.

---

## 27. Submission checklist

- [ ] Public repo with a visible MIT license in the About section
- [ ] Public YouTube video under 3 minutes, showing the working product
- [ ] Hosted demo URL or complete run-from-source instructions
- [ ] Description names PayPal and the AI model; "Built With" filled
- [ ] Sponsor tools ticked (AG Grid; Render if used)
- [ ] Real sandbox payout and a verified webhook shown
- [ ] No API keys committed; secret scan clean
- [ ] Test credentials provided
- [ ] Submission frozen after the deadline

---

## 28. Definition of done

Per `PROJECT_RULES.md §N`: requirement met, scope respected, tests written and passing, full build green with real output reviewed, no secrets, no dead code, docs updated, report delivered.

---

## 29. Human tasks and open decisions

Only the owner can complete these:

1. **Create the public GitHub repository** `leash` and add the MIT license (must appear in the About box). Decide the copyright holder name.
2. **Generate the backend** at start.spring.io (Maven, Java 21, current stable Boot GA; Web, Validation, Data JPA, PostgreSQL, Flyway, Security, Actuator, Testcontainers) and unzip into `backend/`. Do not add Spring AI yet.
3. **PayPal sandbox:** one business account with balance, three personal accounts, one sandbox REST app; confirm **Payouts** is enabled. Put credentials in `.env`. Register the webhook in Slice 1.6.
4. **Role D LLM** (runtime demo agent): provider, model, base URL, API key. Free tiers are acceptable for the demo.
5. **Render** account and the decision to pay for always-on web + Postgres through judging (~Dec 21), or rely on run-from-source instructions.
6. **Approve this reconstructed `AGENTS.md`**, especially §8 (policy order and reason codes) and §16 (API contract, standardized admin prefix `/api/v1/admin/**`).
7. Optional sponsor choices (APIMatic, Zapier, Channel3) — decide after Gate G3.
