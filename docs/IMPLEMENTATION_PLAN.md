# IMPLEMENTATION_PLAN.md: How to build Leash with any coding agent

> Place at `docs/IMPLEMENTATION_PLAN.md`. This file is the **execution manual**.
> `PROJECT_RULES.md` = how we work. `AGENTS.md` = what we build. This file = **in what order, with which tools, and how to know each step is done.**
> It works with any coding agent (OpenCode, Cline, Aider, Kilo Code, Goose, Claude Code, Codex CLI, Cursor, ...) and any model that can edit files and call tools.
>
> Tool and pricing facts below were checked by web search on **Tue Nov 10, 2026**. Model rankings and free-tier limits change weekly: re-check before you depend on them.

---

## 1. The clock (IST)

| | |
|---|---|
| Now | Tue Nov 10, ~15:30 IST |
| Hard deadline | Fri Nov 13, **01:30 IST** (Thu Nov 12, 12:00 pm PT) |
| **Your deadline** | **Thu Nov 12, 18:00 IST**: submitted. The remaining 7.5 hours are buffer for YouTube processing, form problems, and sleep |
| Build budget | ~20 hours (matches `AGENTS §26`) |

| Block (IST) | Hours | Phase |
|---|---|---|
| Tue 16:00 to 23:00 | 7 | Phase 0 (1h) + Phase 1 (5h) + Slice 2.1 (1h) |
| Wed 09:00 to 22:00 | 8 | Rest of Phase 2 (4h) + Phase 3 (4h) |
| Thu 10:00 to 18:00 | 5 | Phase 4 (2h) + Phase 5 (3h) |

Wall-clock blocks include meals, breaks, and overrun slack; the "Hours" column is working time and matches the 20-hour budget.

**Decision gates** (if a gate fails, apply the cut order in `AGENTS §3.2`; do not push through):

| Gate | When (IST) | Question | If no |
|---|---|---|---|
| G1 | Tue ~21:00 | Did a **real sandbox payout** succeed (Slice 1.4)? | **Stop.** Fix account/permissions or ask in the PayPal Discord. Do not switch APIs silently |
| G2 | Wed ~13:00 | Does `POST /payments` work end to end with the 3 scenes via curl? | Cut velocity limit and the prompt-injection invoice |
| G3 | Wed ~22:00 | Is the full flow clickable in the browser? | Cut policy-editor UI and live updates (keep 3-second polling) |
| G4 | Thu ~13:00 | Is it deployed and working on a public URL? | Freeze features. Only README, video, and submission remain |
| G5 | Thu 18:00 | Submitted? | Submit what works. A working smaller project beats a missing one |

---

## 2. Findings that change how you build (read first)

1. **Spring Boot 4 + Spring AI 2.x is the current line.** Spring AI 2.0 went GA on June 12, 2026 and requires Spring Boot 4.0/4.1 and Spring Framework 7. Spring Boot 3.5 reached end of life on June 30, 2026. Most models were trained mostly on older code, so **expect agents to emit Spring AI 1.x or Boot 3 patterns** (old option setters, old starter names, Jackson 2 imports). Rules:
   - A human generates the starter project at **start.spring.io** (Slice 0.1). Agents must not hand-write the `pom.xml` from memory.
   - Every slice that touches Spring AI, Spring Security, Hibernate, or Flyway starts with: "check the installed version and read its docs."
   - The LLM sits behind the `AgentModelClient` port (`AGENTS §6`), so a failure is contained to one class. See Gate L in Slice 2.9.
2. **Render free tier will break your demo during judging.** Free web services sleep after 15 minutes idle (about 1 minute to wake). **Free Postgres expires 30 days after creation** (then 14 days of grace). A database created Nov 11 expires around Dec 11, inside the Dec 1 to 15 judging window. **Plan to pay** (roughly $7/month for an always-on web service plus the smallest paid Postgres; confirm on Render's pricing page) from deploy until winners are announced (~Dec 21), or make sure run-from-source instructions work.
3. **Free LLM tiers are too small to drive a coding agent.** A coding agent re-sends big context on every step, so one task becomes 10 to 20 requests. Free caps (for example OpenRouter `:free` models at 20 requests/minute and 50/day without credit) vanish quickly. Use free tiers for the **demo agent** (few calls), and spend a few dollars of paid credit on a hosted open-weight model for **coding**.
4. **`AGENTS.md` is the portable context file.** OpenCode and Codex CLI read it natively; Cline falls back to it when no `.clinerules/` exists (per a third-party adapter reference; confirm in Cline's docs); Aider loads it through config; Claude Code uses `CLAUDE.md` with an `@AGENTS.md` import. Section 3.3 sets this up so every agent sees both rule files.
5. **Some tools are gone or in flux.** Roo Code's repo was archived in May 2026 (its community moved to Kilo Code), and Gemini CLI was reported discontinued. Do not build your workflow on them.

---

## 3. Tooling setup (Hour 0, ~45 minutes, parallel with Slice 0.1)

### 3.1 Pick ONE coding agent and stay with it for the whole build

| Agent | Type | Why pick it | Loads rules |
|---|---|---|---|
| **OpenCode** (MIT) | Terminal | Model-agnostic (75+ providers), plan and build modes, works with local models | `AGENTS.md` natively |
| **Cline** (Apache-2.0) | VS Code extension | Step-by-step approvals, good if you want to see every diff | `.clinerules/`, else `AGENTS.md` |
| **Aider** | Terminal | Git-native: every change is a commit, easy to undo | `read:` list in `.aider.conf.yml` |
| **Kilo Code** | VS Code / JetBrains | Cline/Roo-style agent, bring your own key | its rules dir / `AGENTS.md` |
| **Claude Code, Codex CLI, Cursor** | Commercial | Use if you already have access | `CLAUDE.md` / `AGENTS.md` / `.cursor/rules` |

Recommendation: **OpenCode or Cline** as the main agent. Keep Aider as a second tool for small surgical edits. Because the context lives in `AGENTS.md`, you can switch agents mid-project if one hits a rate limit. Follow each tool's official install page; do not copy install commands from blog posts.

### 3.2 Pick models by ROLE, not by leaderboard

Open-weight families that were competitive when this was written (check what your provider serves *today*): **Qwen3.x / Qwen3-Coder, DeepSeek V4, GLM-5.x, Kimi K2.x, MiMo V2.5**. Benchmark numbers in blog roundups are vendor-reported and often contaminated, so run the bake-off below instead of trusting them.

| Role | Used for | Requirement |
|---|---|---|
| **A. Money-critical builder** | Slices 1.5, 2.1, 2.2, 2.5, 2.6, 3.2 | The strongest model you can access. **Not** a small local model |
| **B. Bulk builder** | UI, Dockerfile, README, boilerplate tests, polish | Cheaper and faster is fine |
| **C. Reviewer** | Review of Role A diffs | A **different model family** than Role A, so errors are not correlated |
| **D. Runtime LLM** (the demo agent inside Leash) | Slice 2.9 | Reliable tool calling; served over an OpenAI-compatible API |

**Where to get open-weight models**
- **Hosted, one key (best default):** OpenRouter (many open-weight models; `:free` variants are rate-capped, and credit raises the cap), or any provider offering an OpenAI-compatible API. A few dollars covers this whole project.
- **Free, fast inference for Role D:** Groq or Cerebras (open-weight models, free tiers, no card at the time of writing). Cerebras' free tier reportedly caps context at 8K tokens: fine for the demo agent, **not** for a coding agent.
- **Local with Ollama:** good for Role D and small edits. Running a ~30B-class model well needs roughly 24 GB of GPU or unified memory. On a laptop without that, do not use local models for Phases 1 and 2.
- Free-tier prompts may be used to train models. Never send real secrets or personal data. (Leash's demo data is fake by design.)

**Bake-off (20 minutes, once, before Slice 1.1).** Give 2 to 3 candidate models the **same** Slice 2.1 prompt (policy engine + tests). Pick the one whose output compiles and passes with the fewest edits. Use it for Role A. Use a different one for Role C.

### 3.3 Make every agent see both rule files

1. Copy `AGENTS.md` and `PROJECT_RULES.md` into the repo root.
2. Add as the **first lines** of `AGENTS.md`: `> Read PROJECT_RULES.md first. It defines workflow and approval rules; this file defines scope, stack and domain rules.` (This also closes open item 4 in `PROJECT_RULES.md §O`.)
3. Claude Code only: create `CLAUDE.md` containing `@AGENTS.md` and `@PROJECT_RULES.md`.
4. Aider only: `.aider.conf.yml` with `read: [AGENTS.md, PROJECT_RULES.md]`.
5. Create `docs/PROGRESS.md` (the agent keeps it updated: done, assumptions, blockers, next slice).

### 3.4 Safety with agents
- Work on a branch per phase. `main` stays green (`PROJECT_RULES §J`).
- Run the agent with approval required for: deleting files, `git push`, installing packages, and any command with network side effects. Auto-approve only read and build/test commands.
- Only **sandbox** PayPal credentials ever exist on your machine. Keep the coding agent's LLM key separate from the demo app's key, and set a spend limit on both.
- Agents can read `.env`. Treat anything in it as visible to your model provider.

---

## 4. The slice loop (the same for every slice, with any agent)

1. **Fresh session per slice** (clean context). Paste the **Kickoff prompt** below with the slice ID.
2. The agent states: files it will touch, tests it will write first, dependencies it wants. You say go.
3. **Test first**, then implement, in small steps.
4. The agent runs `./mvnw -B verify` (plus `npm run build && npm test` for frontend slices) and pastes the **real tail of the output**.
5. **You review the diff** (checklist in section 6), then commit with a Conventional Commit message.
6. The agent updates `docs/PROGRESS.md`. Close the session.

**Time-box each slice (45 to 75 minutes).** If a slice has failed the same way twice: stop, paste the error into a **different model**, or use the Stuck prompt. Do not let an agent loop for an hour.

### Kickoff prompt (paste at the start of every session)

```
You are the lead Java engineer on Leash. Before anything else:
1. Read PROJECT_RULES.md and AGENTS.md fully, and docs/PROGRESS.md if it exists.
   From docs/IMPLEMENTATION_PLAN.md read only the slice I name.
2. Do not write code yet. First tell me: the slice ID, the files you will create or modify,
   the tests you will write first, and any dependency you want to add and why.
3. Work test-first, in small steps. Run ./mvnw -B verify (and the frontend build/tests for
   frontend slices). Paste the last lines of the REAL output. If you did not run something, say "not run".
4. Check the versions actually installed (Spring Boot, Spring AI, Spring Security, Hibernate,
   Flyway, AG Grid) and follow THEIR docs, not memory. Do not paste older-version snippets.
5. Stop and ask me for: anything in AGENTS section 29, anything on the out-of-scope list, and any
   STOP-and-approve item in PROJECT_RULES section L. No drive-by changes.
Current slice: <ID>
```

### Stuck prompt
```
You have failed this twice. Stop editing. Write: (1) the exact error, (2) what you assumed,
(3) which assumption the error contradicts, (4) the smallest experiment that would test it.
Run only that experiment and report. Do not change unrelated code.
```

### Review prompt (give to Role C on money-critical slices)
```
Review this diff as a payments engineer. Do not edit. List, with file:line: race conditions,
double-payment paths, missing idempotency, PayPal calls inside a DB transaction, secrets in code/logs,
missing validation, state transitions outside PaymentStateMachine, ledger writes missing, and
tests that pass without asserting anything. Say "none found" only if you checked each item.
```

---

## 5. The build, slice by slice

Each slice lists: **Role** (model), **Prompt**, **Done when**, and **Watch for**. Prompts assume the Kickoff prompt has already been given.

### Phase 0: Bootstrap (Tue 16:00 to 17:00)

**Slice 0.1: Human prep (no agent).** Run in parallel while the agent works on 0.2.
- Create a **public GitHub repo** named `leash` and add the MIT license (it must show in the About box).
- Generate the backend at **start.spring.io**: current stable Boot GA (not RC/milestone), Java 21, Maven, dependencies: Spring Web, Validation, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Spring Security, Actuator, Testcontainers. Unzip into `backend/`. Do **not** add Spring AI yet.
- PayPal sandbox (`AGENTS §29`): one **business** account (with balance) and **three personal** accounts; create a sandbox REST app. Put `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` in a git-ignored `.env`.
- Check in the sandbox dashboard that **Payouts** is enabled for the app. This is the project's biggest external risk.
- Get a Role D key (Groq/OpenRouter/other) and note the exact provider and model for the README.

**Slice 0.2: Skeleton** (Role B)
```
Slice 0.2. Build the repository skeleton exactly as AGENTS section 5, using the backend/ project I
generated from start.spring.io. Add: docker-compose.yml (Postgres 16, local only), application.yml
with profiles local/demo/test and fail-fast @ConfigurationProperties, Flyway V1__init.sql (baseline),
Spotless with google-java-format enforced in verify, .env.example (every variable, placeholders only),
.gitignore, README stub, docs/PROGRESS.md, and .github/workflows/ci.yml (Java 21 + Maven cache +
./mvnw -B verify). Do not add Spring AI. Do not hand-edit dependency versions I did not ask for.
```
**Done when:** `./mvnw -B verify` is green locally and in CI; the app boots against docker-compose Postgres; `/actuator/health` returns UP.
**Watch for:** the agent "fixing" the generated `pom.xml` with older starter names from memory. If a dependency does not resolve, check start.spring.io's generated list.

### Phase 1: PayPal core (Tue 17:00 to 22:00)

**Slice 1.1: Money, config, sandbox guard** (Role A)
```
Slice 1.1. Implement Money(long amountMinor, Currency) with USD-only SupportedCurrency, positive-amount
and hard-cap validation, and a PayPal decimal-string conversion using BigDecimal.movePointLeft at the
adapter boundary only (AGENTS 7.1). Implement PayPalProperties (@ConfigurationProperties, @Validated) and
the startup guard that refuses to boot if base-url is not a sandbox host unless LEASH_ALLOW_LIVE=true (AGENTS 10).
Write the tests first: boundaries, rounding/format ("12.00", "0.01"), negative/zero, cap, guard accepts
sandbox and rejects live.
```
**Done when:** unit tests green; starting with a live URL fails fast.

**Slice 1.2: OAuth client with token cache** (Role A)
```
Slice 1.2. Implement the PayPal OAuth client-credentials flow with Spring RestClient (connect 3s, read 10s),
an in-memory thread-safe token cache refreshed at expires_in - 60s, and one refresh+retry on 401 (AGENTS 10).
Never log tokens or secrets. Use WireMock tests: cache hit, expiry refresh, 401 retry once, timeout.
```
**Done when:** WireMock tests green; a grep of logs in tests shows no token values.

**Slice 1.3: Payouts client, status lookup, status mapper** (Role A)
```
Slice 1.3. Behind the PayPalPayoutsClient port, implement: submit one payout (recipient_type EMAIL,
sender_batch_id "leash-<paymentId>", sender_item_id <paymentId>, PayPal-Request-Id <paymentId>) and
payout status lookup. Retries: exponential backoff, max 3, only timeouts/5xx/429, only with an idempotency key.
Log PayPal-Debug-Id on every error. Put all status translation in PayPalStatusMapper with an exhaustive
table test, storing the raw PayPal status string. READ the current PayPal Payouts docs first; do not guess
field names or statuses. Provide a fake client for other tests.
```
**Done when:** WireMock + mapper table tests green.
**Watch for:** invented JSON field names or statuses. Compare every field to PayPal's docs.

**Slice 1.4: Sandbox spike, then Gate G1** (human + Role A)
```
Slice 1.4. Add a throwaway, profile-guarded runner (not part of production code paths) that submits ONE
real $1.00 payout from my sandbox business account to my sandbox personal account email from env, then
polls status. Run it. Record exactly what happened (statuses, timing, any error + PayPal-Debug-Id) in
docs/decisions/001-payouts-spike.md. Do not change any other code.
```
**Done when:** the sandbox personal account shows the money, and ADR 001 records it. **Gate G1.**

**Slice 1.5: Webhook receiver** (Role A, then Role C review)
```
Slice 1.5. Implement POST /webhooks/paypal per AGENTS 11: read the RAW body bytes before any JSON parsing;
verify through PayPal's verify-webhook-signature API using the transmission headers and PAYPAL_WEBHOOK_ID,
embedding the raw event JSON text verbatim; reject unverified with 400; dedupe by event id via the
webhook_events primary key (duplicate returns 200, no-op); persist the raw event first; respond fast.
Add a Flyway migration. Tests: valid signature accepted, tampered body rejected, duplicate ignored,
out-of-order event cannot move a terminal state backward (use a stubbed state applier for now).
```
**Done when:** the four tests pass.
**Watch for:** the agent parsing to an object and re-serializing before verification (breaks real signatures).

**Slice 1.6: Minimal Dockerfile, `render.yaml`, first deploy, register webhook** (Role B + human)
```
Slice 1.6. Create a multi-stage Dockerfile (backend only for now; non-root; JAVA_TOOL_OPTIONS MaxRAMPercentage=75)
and render.yaml (one Docker web service + one Postgres; secrets with sync:false; health check /actuator/health;
build the JDBC URL from Render's separate host/port/db/user/password values, not the postgres:// URL).
Set server.port=${PORT:8080} and forward-headers-strategy=framework. Do not print any secret.
```
Then you: create the Render service, set env vars, deploy, and **register the webhook** at `https://<service>.onrender.com/webhooks/paypal` in the PayPal dashboard. Put `PAYPAL_WEBHOOK_ID` in Render's env.
**Done when:** running the spike payout (1.4) against the deployed app produces a **real webhook that verifies and is stored**.
**Watch for:** a free Render service asleep when PayPal calls (wake it first). Decide now whether to pay for always-on (section 2, item 2).

### Phase 2: Policy, gateway, demo agent (Tue 22:00 to Wed ~13:00)

**Slice 2.1: Policy engine (pure, TDD)** (Role A; this is also your bake-off prompt)
```
Slice 2.1. Implement the pure policy engine in package policy (NO Spring/JPA imports) exactly per AGENTS 8:
PolicyDecision evaluate(PolicyContext). Fixed evaluation order, collect ALL hard-rule violations, primary reason is
the first in order, UTC calendar budget periods (weeks start Monday), pending approvals reserve budget,
emails normalized before allow-list match. Write the tests FIRST: parameterized per rule, boundaries
(amount == limit, limit + 1), multi-violation, period rollover at UTC midnight and Monday, auto-approve threshold.
Target >= 90% line coverage on the package.
```
**Done when:** tests green and coverage target met. Run the Review prompt with Role C.

**Slice 2.2: Payment state machine (pure, TDD)** (Role A)
```
Slice 2.2. Implement PaymentStateMachine per AGENTS 7.2: one place for all transitions; illegal transitions throw
IllegalStateTransitionException. Tests: every allowed transition and every forbidden one (table-driven), terminal
states never move.
```

**Slice 2.3: Agents, hashed API keys, policies, API-key auth** (Role A)
```
Slice 2.3. Implement agents, policies, and API keys per AGENTS 15 and 16: key format lsh_<32 random bytes base64url>
from SecureRandom, store only SHA-256 hash + 8-char prefix, constant-time compare with MessageDigest.isEqual,
plaintext shown once. Add the stateless API-key security filter chain for /api/v1/payments/** and /api/v1/budget.
Flyway migration. Tests: valid key, wrong key, suspended agent, key never logged (redaction test).
```

**Slice 2.4: Append-only ledger** (Role A)
```
Slice 2.4. Implement ledger_events per AGENTS 13 with a Flyway trigger that rejects UPDATE and DELETE, plus a
LedgerService that writes events in the caller's transaction. Tests (Testcontainers): insert works; UPDATE and DELETE
are rejected by the database; payload never contains secrets.
```

**Slice 2.5: Payment gateway with idempotency and locking (the hardest slice)** (Role A, then Role C review)
```
Slice 2.5. Implement POST /api/v1/payments exactly per AGENTS 9.1: authenticate, validate, require Idempotency-Key,
fingerprint = SHA-256 of canonical body; TX-A locks the agent row (PESSIMISTIC_WRITE), checks (agent, key) for
replay (same fingerprint -> stored response + Idempotent-Replayed header; different -> 422 IDEMPOTENCY_KEY_REUSED),
loads aggregates INSIDE the lock, calls the pure PolicyEngine, persists request + approval (if ESCALATE) + ledger
events, COMMIT; ONLY THEN submit to PayPal (never inside the transaction); TX-B stores ids and sets SUBMITTED.
A DENY is HTTP 201 with decision.outcome=DENY. Use @Version on payment_requests.
Tests first (Testcontainers + WireMock): replay returns an identical body; same key different body -> 422;
two concurrent same-key requests -> exactly one payment and one PayPal call; N threads racing for the last
budget dollars never exceed budget; crash between TX-A and PayPal submit -> stays APPROVED (reconciler test in 2.6).
```
**Done when:** all of the above pass. **This is the slice judges and interviewers will ask about; make sure you can explain it** (section 7).
**Watch for:** `@Transactional` on a method that also calls PayPal; aggregates computed before taking the lock; a `double` anywhere.

**Slice 2.6: Reconciler, GET payment, GET budget** (Role A)
```
Slice 2.6. Implement the @Scheduled reconciler per AGENTS 9.1: retry submission for APPROVED rows older than 60s
(safe via PayPal-Request-Id) and poll PayPal for SUBMITTED rows older than 10 minutes. Implement GET /payments/{id}
(owning agent only) and GET /budget. Tests: simulated crash -> exactly one payout after reconciler; another agent
cannot read the payment (404).
```

**Slice 2.7: Webhook events drive the state machine** (Role A)
```
Slice 2.7. Connect verified webhook events to PaymentStateMachine via PayPalStatusMapper: map payout item events to
SUCCEEDED/FAILED/remain SUBMITTED, write ledger events with actor "paypal-webhook", never regress a terminal state.
Tests: each mapped event, duplicate, out-of-order.
```

**Slice 2.8: Demo agent, SCRIPTED mode + seeds** (Role B)
```
Slice 2.8. In package demoagent (profile "demo" only), implement the SCRIPTED demo agent per AGENTS 14. It must call
Leash ONLY over the public HTTP API with its own agent key (no direct service/repository calls). Tools: check_budget()
and pay_invoice(invoiceId, payeeEmail, amountMinor, currency, memo) with idempotency key "invoice-<invoiceId>".
Add demo/invoices.json: $12 to an allowed payee, $150 to an allowed payee, $900 to an unknown payee.
Add POST /admin/demo/run?mode=SCRIPTED (admin chain comes in Phase 3; for now guard by profile).
```
**Done when (Gate G2, Wed ~13:00):** the three scenes run via curl and via the scripted agent: $12 auto-approves and later SUCCEEDS by webhook; $900 is denied with a reason; $150 sits in PENDING_APPROVAL.

**Slice 2.9: Demo agent, LLM mode (Gate L)** (Role A for the first 60 minutes)

First run the **tool-calling smoke test** against your Role D model. It must return a `tool_calls` entry with valid JSON arguments in **3 out of 3** runs:
```
curl -s "$LLM_BASE_URL/chat/completions" \
  -H "Authorization: Bearer $LLM_API_KEY" -H "Content-Type: application/json" -d @- <<EOF
{ "model": "$LLM_MODEL", "temperature": 0,
  "messages": [{"role":"user","content":"Pay invoice inv-1 for 12 dollars to a@b.com"}],
  "tools": [{"type":"function","function":{"name":"pay_invoice","description":"Pay an invoice",
    "parameters":{"type":"object","properties":{"invoiceId":{"type":"string"},"amountMinor":{"type":"integer"}},
    "required":["invoiceId","amountMinor"]}}}] }
EOF
```
Switch Role D models until one passes. Then:
```
Slice 2.9. Implement the LLM-mode demo agent behind the AgentModelClient port using Spring AI tool calling
(provider, model, key and base URL from env; add LEASH_LLM_BASE_URL to .env.example). READ the docs for the
installed Spring AI version first (2.x differs from 1.x). Same tool implementations as SCRIPTED mode; max 8 tool
iterations; timeout; low temperature; system prompt says deny/escalate are FINAL outcomes to report and splitting
payments to evade limits is forbidden. The app must boot and ALL tests pass with NO LLM key (mock the client).
Show the agent's final summary in the response of /admin/demo/run.
```
**Gate L (time-box 60 minutes).** If Spring AI tool calling does not work against the installed version, **stop and ask the owner to approve** one of: (a) LangChain4j (already permitted in `AGENTS §4` if Spring AI is blocked, with an ADR), or (b) a thin direct call to the provider's OpenAI-compatible tool-calling API behind the same port (needs an ADR and your approval; it deviates from the fixed stack). Both keep SCRIPTED mode as the safety net for the video.

### Phase 3: Approvals and dashboard (Wed ~13:00 to ~22:00)

**Slice 3.1: Admin security chain, CSRF, agents/policy/kill-switch endpoints** (Role A)
```
Slice 3.1. Implement the admin filter chain per AGENTS 15 (HTTP Basic or form login with credentials from env, BCrypt in
memory, no default password; CSRF with cookie token repository) and the admin agent endpoints in AGENTS 16
(create agent returns key once, list with spend vs budget, PUT policy with ledger event, suspend/resume, rotate-key).
Tests: an AGENT key can NOT call any /api/v1/admin/** endpoint; admin without CSRF token is rejected on writes;
policy change writes a ledger event.
```

**Slice 3.2: Approvals** (Role A, then Role C review)
```
Slice 3.2. Implement the approval workflow per AGENTS 12: approve moves APPROVED and triggers submission as in
9.1 steps 5-6; reject releases budget; both idempotent and race-safe with @Version (approve racing reject has
exactly one winner); on approve re-check hard rules (suspended agent, payee removed) and reject with a ledger
event if violated; record decided_by and note. Tests: double approve is a no-op; approve vs reject race;
re-check rejection.
```

**Slice 3.3: Admin payments list, ledger timeline, manual refresh** (Role B)
```
Slice 3.3. Implement GET /admin/payments (paged, filter by status/agentId/from/to, max page size enforced,
no N+1), GET /admin/payments/{id}/events, POST /admin/payments/{id}/refresh. DTO records only; entities never
returned. Test pagination, filters, and query count on the list endpoint.
```

**Slice 3.4: Frontend scaffold** (Role B)
```
Slice 3.4. Create frontend/ with Vite + React + TypeScript strict, TanStack Query, plain CSS variables (light and
dark), and src/api/{client.ts,types.ts} (all HTTP calls in one place; handles CSRF header X-XSRF-TOKEN and
ProblemDetail errors). Vite dev proxy to the backend. No UI component library, no Redux.
Every panel needs loading, empty and error states.
```

**Slice 3.5: AG Grid ledger + timeline drawer** (Role B; check installed AG Grid version docs first)
```
Slice 3.5. Build the ledger grid per AGENTS 21 using AG Grid Community: columns time/agent/payee/amount/decision/
reason/PayPal status/correlation id (hidden), sorting, per-column filters, quick filter, server-side pagination from
/admin/payments, getRowId = payment id, refetchInterval 3000. Row click opens a drawer with the payment's ledger events.
Read the DOCS for the installed AG Grid version (module registration and theming changed across versions) before writing grid code.
Status and decision shown as text plus color. Money as integers in minor units, formatted only for display.
```
**Watch for:** grid code copied from an older AG Grid major version.

**Slice 3.6: Approvals, budget bars, kill switch, policy editor, demo runner** (Role B)
```
Slice 3.6. Add the approvals panel (Approve/Reject with a confirmation dialog showing payee and amount, buttons
disabled while in flight), budget bars (spent and reserved vs limit), header agent selector and kill switch, policy
editor drawer, and the demo runner (visible only under the demo profile) with SCRIPTED/LLM toggle and the agent's
final summary. Keep API types in src/api/types.ts.
```
**Done when (Gate G3, Wed ~22:00):** you can run all three scenes **from the browser** only.

**Slice 3.7: Design pass** (Role B, only if G3 passed with time left)
```
Slice 3.7. Polish only: spacing scale, one accent color, typography, empty states, focus outlines, responsive layout,
light/dark. No new features. It must look like a restrained fintech console, not a default template.
```

### Phase 4: Harden and document (Thu 10:00 to 12:00)

**Slice 4.1: Production image + final deploy** (Role B)
```
Slice 4.1. Finish the multi-stage Dockerfile (node build -> maven build with frontend output in static/ -> JRE 21,
non-root) and final render.yaml. Enable the demo profile on the Render deployment only. Verify from a clean clone:
docker compose up (Postgres) + ./mvnw spring-boot:run works from README instructions alone.
```
**Slice 4.2: Docs** (Role B)
```
Slice 4.2. Write README with every section in AGENTS 24 (what/why, architecture diagram in Mermaid, run-from-source,
test credentials for judges, the exact LLM provider and model, sandbox-only statement, what is new since Oct 1, limits
and "not production-ready"), docs/architecture.md (request flow + state machine), and LICENSE check.
```
**Slice 4.3: Secret scan and clean-clone test** (human + Role B)
Run a secret scan (`gitleaks` or the grep in `AGENTS §15`) over **the full git history**. If anything leaks, **rotate it** (deleting the commit is not enough). Then clone into an empty folder and follow only the README.
**Done when (Gate G4, Thu ~13:00):** public Render URL works end to end, including a real webhook; fresh clone runs.

### Phase 5: Video and submit (Thu 13:00 to 18:00)

**Slice 5.1: Record** (human). Use SCRIPTED mode for reliable takes and show one LLM-mode run. Record twice, keep the best. Under 3:00.

| Time | Shot |
|---|---|
| 0:00 to 0:15 | The problem, one sentence: "Companies won't give an AI agent an open wallet." Show Leash between the agent and PayPal |
| 0:15 to 0:50 | **Auto-approve:** agent pays $12; grid row appears; webhook flips it to SUCCEEDED |
| 0:50 to 1:20 | **Deny:** $900 to an unknown payee; blocked with a visible reason (optionally the injected invoice) |
| 1:20 to 2:00 | **Escalate:** $150 waits; click Approve; money moves |
| 2:00 to 2:30 | Ledger timeline for one payment; kill switch; budget bar |
| 2:30 to 2:55 | "What's next": multi-agent, reputation, more providers. Name PayPal, the AI model, AG Grid (and Render) |

No third-party trademarks or copyrighted music. Upload to **YouTube as public** right away (processing can take hours).

**Slice 5.2: Submit** (human, by 18:00 IST). Complete the Devpost form (repo URL, video URL, demo URL, text description naming PayPal and the model, Built With, sponsor tools ticked: AG Grid, and Render if used). Tag `v1.0-submission`. **Then freeze: no commits, README edits, or video swaps until winners are announced.**

---

## 6. Per-slice review checklist (you, 5 minutes, before every commit)

- [ ] Only the files the slice needed changed. No unrelated refactors.
- [ ] Tests exist and **assert something**. None skipped, disabled, or weakened.
- [ ] You saw the real build output, not just the agent's claim.
- [ ] Money is `long` minor units end to end. No `double`/`float`.
- [ ] No PayPal or LLM call inside a database transaction that holds a lock.
- [ ] State changes go through `PaymentStateMachine` and write a ledger event in the same transaction.
- [ ] No secrets, tokens, or keys in code, logs, tests, or the frontend bundle.
- [ ] New dependencies were announced, justified, and are in `PROGRESS.md`.
- [ ] The commit message follows Conventional Commits.

## 7. Explain-it drill (do after each phase; this is what judges and interviewers test)

Ask the agent: "Explain this phase's request flow in 5 sentences, then ask me 3 hard questions about it." Answer out loud. You must be able to explain, without notes: why the agent row is locked, why PayPal is called after commit, what the idempotency key and fingerprint do, why a DENY is HTTP 201, why the ledger is append-only, and why LLM output is treated as untrusted. That understanding, not the repo alone, is what supports the startup story and a hiring conversation.

## 8. Risks and fallbacks

| Risk | Likelihood | Fallback |
|---|---|---|
| Payouts not enabled on the sandbox app | Medium | Gate G1. Ask the PayPal Discord. Do not switch APIs without your approval |
| Agent emits Spring AI 1.x / Boot 3 code | **High** | Starter from start.spring.io, version-check step in the Kickoff prompt, Gate L |
| Webhook signature fails on valid events | Medium | Raw-body handling (Slice 1.5); log `PayPal-Debug-Id`; use `/refresh` as manual fallback |
| Free LLM key rate-limited during recording | Medium | Record with SCRIPTED mode; show one LLM run if it works |
| Render service asleep or free DB expires | **High** | Pay for always-on web + Postgres through ~Dec 21, or verify run-from-source instructions |
| A slice overruns by more than 30% | High | Stop. Apply the cut order (`AGENTS §3.2`) |
| Agent loops or burns credit | Medium | 45 to 75 minute time-box, Stuck prompt, spend limits on keys |
| Secret leaks into git | Low, severe | Secret scan before first push and before submission; rotate immediately |

## 9. Definition of done for the whole project
`AGENTS §27` is the checklist. Do not skip: public repo with visible MIT license, hosted demo or clean run-from-source instructions, real sandbox payout plus verified webhook shown, named AI model driving payments, public YouTube video under 3 minutes, and the freeze after submission.
