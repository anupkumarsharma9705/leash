# Devpost submission pack

Copy-paste text for the submission form and the YouTube description. Replace the two placeholders
before publishing: `<REPO_URL>` and `<VIDEO_URL>`.

**Read the honesty block before editing anything.** This build does not call an LLM and has not
completed a real PayPal sandbox payout or a live webhook. Presenting those as done would be false.

---

## Fields

**Project name:** Leash

**Tagline:** Spending controls for AI agents that pay with PayPal.

**Elevator pitch (one sentence):** Leash is a policy gateway between an AI agent and PayPal, so a
model with a payment tool can never spend outside the budget, payee list, or approval threshold its
operator set.

**Built With (tick these):**

- Java 21
- Spring Boot 4.1.2
- Spring Security 7
- PostgreSQL 16
- Flyway
- Maven
- JUnit 6
- Testcontainers
- WireMock
- React 19
- TypeScript 7
- Vite 8
- TanStack Query 5
- AG Grid Community 36
- Docker
- GitHub Actions
- PayPal Payouts (sandbox)
- Render (blueprint prepared; not deployed — tick only if you deploy it)

**Do not tick** an LLM, model provider, or AI SDK. None is wired in this build.

**Repository:** `<REPO_URL>`

**Video:** `<VIDEO_URL>`

**Hosted demo:** none. Use the run-from-source instructions in the repo README. The multi-stage
Docker image builds and was smoke-tested locally.

**Test credentials:** `admin` / `admin-test-password` for the local console. These are local-only
demo credentials, not a shared deployment secret. Change them for any public URL.

### Try it out (run from source)

Prerequisites: Java 21, Node 22+, Docker, and Git. Full detail is in the repository README §4.

```powershell
git clone <REPO_URL>
cd leash
docker compose up -d

# Copy the sample env and set ADMIN_PASSWORD_HASH to the documented local test hash
Copy-Item .env.example .env

# Terminal A
cd backend
$env:SPRING_PROFILES_ACTIVE='local,demo'
.\mvnw.cmd spring-boot:run

# Terminal B
cd frontend
npm ci
npm run dev
```

Open <http://localhost:5173>, sign in as `admin` / `admin-test-password`, then open **Demo** and click
**Run the demo**. No PayPal credentials are needed for the three policy scenes; without them the `$12`
payment stays `APPROVED` by design.

---

## Long description

### Inspiration

An AI agent with a payment tool is one prompt injection away from emptying an account. The usual
guardrail is a line in the agent's own code — which the agent, or whoever controls its prompt, can
change. A limit that lives inside the thing being limited is not a control.

Leash moves the decision to a separate process. The agent asks to pay; Leash checks a policy the
operator owns and records every fact on the way. The tagline we kept coming back to:
**"Companies won't give an AI agent an open wallet."**

### What it does

Every payment request gets one of three outcomes:

- **APPROVE** — inside every hard rule and at or below the auto-approve threshold. Leash submits a
  PayPal sandbox Payout.
- **ESCALATE** — inside every hard rule but above the threshold. The amount reserves budget and waits
  for a human. Approving re-runs the entire policy against the current week before anything is sent.
- **DENY** — a hard rule failed. No money moves. Every reason code is returned to the agent and
  written to the ledger.

The hard rules, in fixed order: agent active, USD only, positive amount, under the $1,000 per-payment
cap, payee on the normalized allow-list, and spent + reserved + amount within the weekly budget. The
budget week is UTC Monday 00:00 to the next Monday 00:00. Pending approvals reserve budget; denials,
rejections, and failures release it.

The operator console shows the ledger, a payment timeline drawer, pending approvals, budget bars, the
policy editor, a kill switch, and a demo runner. The ledger is an append-only PostgreSQL table with a
trigger that rejects `UPDATE`, `DELETE`, and `TRUNCATE`.

### How we built it

**The decision is committed before PayPal is called.** The payment gateway locks the agent row,
checks the idempotency key, aggregates the week's spend and reservations, runs a pure Java policy
engine, and persists the payment, approval, and ledger facts in one short transaction. Only after
that commit does it call PayPal, outside every transaction. A crash in between leaves an `APPROVED`
payment that the reconciler can finish, rather than a payout with no record.

**Idempotency is end to end.** The API requires an `Idempotency-Key`; a replay returns the stored
decision with `Idempotent-Replayed: true`, and the same key with a different body is `422`. PayPal gets
the payment id as `PayPal-Request-Id`, so a retry cannot create a second payout.

**State moves in one place.** A pure state machine is the only code allowed to transition a payment.
Terminal states never move, even for out-of-order webhooks or a reconciler retry. Webhook signatures
are verified over the raw request bytes, events are deduplicated by PayPal event id, and only
`PAYMENT.PAYOUTS-ITEM.*` events can change a payment.

**The console is server-paged.** AG Grid Community 36 renders the ledger with status and agent
filters applied server-side, page-local quick and column filters, a three-second refresh, and a
timeline drawer fed by the ledger. Money is integer minor units end to end; no floating-point
arithmetic touches an amount.

**Security is tested, not asserted.** Agent keys are stored as SHA-256 hashes and compared in constant
time. Admin routes use session-capable HTTP Basic with BCrypt and cookie-backed CSRF, on a separate
security chain; an agent key cannot reach an admin route, and there is a test for it. Admin mutations
take the same agent row lock as payment decisions. No default admin password exists: the app refuses
to boot without a valid BCrypt hash.

### The demo

The `demo` profile provisions **Invoice Agent** and runs three invoices in deterministic `SCRIPTED`
mode. The agent calls Leash only over the public HTTP API with its own bearer key; it has no database
or service access.

| Invoice | Amount | Result |
|---|---|---|
| `inv-1201` | $12.00 to an allowed payee | `APPROVE → APPROVED` |
| `inv-1502` | $150.00 to an allowed payee, above the $50 threshold | `ESCALATE → PENDING_APPROVAL` |
| `inv-9003` | $900.00 to an unknown payee | `DENY / PAYEE_NOT_ALLOWED` |

Running it again replays all three by invoice id: identical payment ids, no second payout, no change
to spent or reserved budget.

### Challenges

- **Keeping the PayPal call outside the lock.** The easy version submits inside the transaction and
  holds a row lock across a network call. We split it into decide-and-commit, submit, then record,
  and made the reconciler the recovery path.
- **Making approval safe.** An approval is not "click yes and pay". It re-runs the full policy with
  this payment's own reservation removed, auto-rejects if any hard rule now fails, and serializes
  against the payment path on the agent row. Approve racing reject has exactly one winner.
- **Making the ledger genuinely append-only.** A trigger rejects updates and deletes, and the
  application has no repository method for either. Tests prove a payload containing a private-key
  header is rejected.
- **AG Grid 36 specifics.** Module registration and theming changed across majors; the grid now uses
  `AllCommunityModule` and a `themeQuartz` object shared with the app's light/dark store. Enter-to-open
  moved to a capture-phase handler because the cell event no longer exposes the keyboard event.
- **CI honesty.** The first CI file only verified the backend. It now also runs `npm ci`,
  `npm run build`, and `npm run lint`.

### What was verified

- Backend `./mvnw -B verify`: **364 tests**, 0 failures, 0 errors, 0 skipped; Spotless clean; policy
  package coverage **97.5%** (115/118) against a 90% gate.
- Frontend TypeScript strict build and lint: clean.
- Live browser pass: sign-in, server filters, paging, timeline, Escape/focus restoration, approval
  confirmation and approval, policy save, suspend/resume, demo gating, and a scripted run whose repeat
  replayed all three payments.
- Multi-stage Docker image built locally (`leash:local`, 572 MB) and run against a fresh PostgreSQL
  database: five Flyway migrations, packaged console and hashed assets served from the jar,
  `/actuator/health` `UP`, unauthenticated admin and agent calls `401`, CSRF-protected scripted demo
  correct, second run fully replayed.
- Working-tree secret scan clean apart from intentional fake fixtures in tests; `.env` absent and
  ignored.

### What was not verified

- **No real PayPal sandbox payout.** No `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, or funded
  recipient was available. The adapter and failure mapping are covered by WireMock tests, but that is
  not Gate G1. The `$12` payment stays `APPROVED` by design when credentials are absent.
- **No live PayPal webhook.** No `PAYPAL_WEBHOOK_ID` and no public endpoint. Signature verification,
  dedupe, and state application are tested against fakes and WireMock.
- **No LLM is called.** LLM mode is not implemented in this build; the route fails closed with
  `501 LLM_NOT_CONFIGURED`, and no provider, model, or key was chosen. `SCRIPTED` is the demo path.
  The project is infrastructure *for* AI agents, not an LLM application in this build.
- **No Render deployment.** The blueprint is in the repo; no account was available.

### What we learned

A spending control is mostly an evidence problem. The interesting work was not the policy `if`
statements; it was making the sequence crash-safe, making a retry unable to pay twice, making an
approval re-check the world, and making the audit trail impossible to edit. Those properties are what
let an operator trust an agent with a payment tool at all.

### What's next

Connect the PayPal sandbox credentials and complete Gate G1, register the webhook on a deployed
instance, then implement LLM mode behind a new `AgentModelClient` port and complete Gate L. After that: more rails,
per-agent velocity limits, and team approvals. Leash is sandbox-only, USD-only, and not
production-ready today.

---

## Short description (if the form has a character limit)

Leash is a spending-control gateway between an AI agent and PayPal. The agent asks to pay; Leash
checks an operator-owned weekly budget, payee allow-list, per-payment cap, and auto-approve threshold,
then approves, escalates to a human, or denies. Every decision, reservation, submission, and status
change lands in an append-only ledger. Built with Java 21, Spring Boot 4, PostgreSQL, React,
TypeScript, and AG Grid Community. PayPal Payouts sandbox is the money rail. This build runs a
deterministic scripted demo agent; no LLM is called, and no real payout or live webhook was verified
because sandbox credentials were unavailable. Sandbox only; not production-ready.

---

## YouTube description

Leash — spending controls for AI agents that pay with PayPal.

An agent with a payment tool can empty an account. Leash sits between the agent and PayPal and makes
the policy decision outside the agent: weekly budget, payee allow-list, per-payment cap, and a human
approval threshold. Every fact goes to an append-only ledger.

The demo runs three invoices: $12 auto-approves, $150 escalates for human approval, and $900 to an
unknown payee is denied. Running it again replays all three with no second payout.

Stack: Java 21, Spring Boot 4, PostgreSQL, Flyway, React, TypeScript, Vite, TanStack Query, AG Grid
Community, Docker. Money rail: PayPal Payouts sandbox.

Honest limits: this build runs the demo agent in SCRIPTED mode and calls no LLM. A real PayPal
sandbox payout and a live webhook were not verified because sandbox credentials were unavailable; the
integration is covered by WireMock tests only. Sandbox only. Not production-ready.

Repo and run-from-source instructions: `<REPO_URL>`

---

## Honesty block — keep this true

| Claim | Status |
|---|---|
| PayPal Payouts sandbox adapter implemented | **Yes**, behind `PayPalPayoutsClient` |
| Real sandbox payout completed | **No** — credentials unavailable |
| Live webhook verified | **No** — no webhook id or public endpoint |
| LLM provider/model called | **No** — `LLM` returns `501`, demo is `SCRIPTED` |
| AG Grid Community used | **Yes**, 36.3.0 |
| Render deployment live | **No** — blueprint only |
| Backend tests green | **Yes**, 364 |
| Docker image built and smoke-tested | **Yes**, locally |
| Real money moved | **Never** — sandbox only |

If credentials arrive before the deadline, complete Gate G1, register the webhook, re-record the parts
of the video that show `SUBMITTED`/`SUCCEEDED`, and update this table. Do not edit the table to claim a
result that was not observed.
