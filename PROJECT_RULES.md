# PROJECT_RULES.md: Engineering Guidelines for Leash

> **Primary engineering guideline for this project.** Every contributor (human or AI) reads this file before starting work.
> It defines **how we work**: standards, workflow, and AI-assisted development rules.
> `AGENTS.md` defines **what we build**: scope, fixed stack, state machine, API contract, milestones.

---

## 0. How this file works

### 0.1 Keywords
- **MUST / MUST NOT**: non-negotiable.
- **SHOULD**: the default; deviate only with a one-line reason in the commit message or `docs/decisions/`.
- **MAY**: optional.

### 0.2 Relationship to `AGENTS.md`
- `PROJECT_RULES.md` is the primary guideline. `AGENTS.md` supplies Leash-specific technical rules and is referenced below as `AGENTS §N`.
- The scope (`AGENTS §3`) and stack (`AGENTS §4`) are **pre-approved decisions**. Working inside them needs no extra approval.
- Anything that **changes or contradicts** those decisions, or any other conflict between the two files, is a **STOP-and-ask** event (see §L, Step 3). Do not silently pick a winner.

### 0.3 Priority when rules conflict
1. Hackathon eligibility and security
2. Correctness of money movement and data integrity
3. A working end-to-end demo
4. Maintainability
5. Polish

### 0.4 Project status (verify, do not assume)

| Item | Status | Source |
|---|---|---|
| Application code | Not confirmed. No project folder was available when this file was written. | Inspect the repo first |
| Language / framework / build / DB / frontend | Decided on paper: Java 21, Spring Boot (pinned GA), Maven, PostgreSQL + Flyway, React + TypeScript + Vite, AG Grid Community | `AGENTS §4` |
| Actual versions in use | Unknown until `pom.xml` and `package.json` exist | Read those files |
| Java group ID / base package | Default `com.leash`, owner may change | `AGENTS §29` |
| LLM provider and model | Open (human decision) | `AGENTS §29` |
| PayPal sandbox credentials, webhook ID, Render account | Open (human tasks) | `AGENTS §29` |

If the files in the repo disagree with this table, **the repo files win**. Record the difference in `docs/PROGRESS.md` and tell the owner.

---

## A. General Development Principles

1. Write clean, readable, production-quality code. Another developer, or the owner on stage, must be able to explain it.
2. Apply SOLID where it reduces risk. Use a design pattern only when it solves a real problem in this codebase (for example: ports and adapters for PayPal and the LLM).
3. Prefer the simplest solution that works end to end. No speculative abstractions, no "framework of the future".
4. DRY: one place per rule. If the same logic appears twice, extract it. Three similar lines are better than a wrong abstraction, so extract when the duplication carries business meaning.
5. KISS and separation of concerns: each class has one reason to change.
6. **Understand the requirement before coding.** If you cannot state in one sentence what the change must do and how it will be verified, stop and clarify.
7. Name things for what they mean in the domain (`PaymentRequest`, `PolicyDecision`), not for how they are built (`Helper`, `Manager`, `Util`).
8. Delete dead code. Do not leave commented-out code or unexplained TODOs. A TODO MUST reference a task in `docs/PROGRESS.md`.

---

## B. Project Architecture

Detailed layout: `AGENTS §5` and `§6`. Binding rules:

1. **One deployable modular monolith** with clear boundaries:
   - **Frontend** (`frontend/`): UI only. It talks to the backend over HTTP and nothing else.
   - **Backend** (`backend/`): all business logic, security, and integrations.
   - **Database**: PostgreSQL, accessed only through repositories.
   - **External integrations** (PayPal, LLM): only behind ports with a real adapter and a test fake.
2. **Layers inside each feature:** `api` (controllers, request/response DTOs) → `app` (use-case services, transactions) → `domain` (entities, value objects, pure rules) → `infra` (repositories, adapters). Dependencies point inward.
3. Controllers: map DTO → call **one** service method → map result. **No business logic. No repository or database access.**
4. Features talk to each other through service interfaces, never through another feature's repositories.
5. The **policy engine** and the **payment state machine** are pure Java: no Spring, JPA, or I/O imports. Plain JUnit tests, no application context.
6. Inject `java.time.Clock`; never call `Instant.now()` in business code.
7. Avoid tight coupling: depend on interfaces at integration boundaries, and keep configuration in `@ConfigurationProperties` classes, not scattered `@Value`s.
8. Packages are by **feature**, lower-case, singular (`payment`, `ledger`). Follow the existing naming once code exists.

---

## C. Java and Spring Boot Standards

1. Use the Java version configured in the build (expected: 21). Use modern features that improve clarity: `record` for DTOs and value objects, `sealed` types and `switch` patterns where they simplify, `var` only when the type is obvious. No preview features.
2. **Constructor injection only.** No field injection, no `@Autowired` on fields, no service locators.
3. **DTOs for every API request and response** (preferably `record`s). **Never expose JPA entities** through controllers.
4. **Validation:** Bean Validation annotations on request DTOs (`@Valid`, `@NotNull`, `@Positive`, `@Size`, ...). Re-check business invariants in the service layer; DTO validation is not enough.
5. **Centralized exception handling** with one `@RestControllerAdvice` that returns the project's error format (`AGENTS §16`). Use specific domain exceptions with meaningful messages. No stack traces or internal details in responses.
6. **Transactions:** `@Transactional` on application services only. Keep them short. **Never make a network call (PayPal, LLM) inside a transaction that holds locks** (`AGENTS §9`). Mark read-only methods `readOnly = true`.
7. Avoid static helpers with state and global mutable state. Stateless pure static functions are fine.
8. **No N+1 queries:** use fetch joins, `@EntityGraph`, or projections for collections; check the SQL log on list endpoints.
9. **Pagination is mandatory** for any list that can grow (ledger, approvals, payments). Enforce a maximum page size server-side.
10. **REST conventions:** nouns for resources, correct verbs and status codes (`201` + `Location` on create, `204` on empty success, `400` validation, `401/403` auth, `404`, `409` conflict, `422` semantic errors, `429` throttling), JSON in camelCase, ISO-8601 UTC timestamps.
11. No Lombok; use records and explicit code (`AGENTS §4`).
12. Format with Spotless/google-java-format; the build MUST fail on formatting violations once configured.

---

## D. Database Standards

Details: `AGENTS §17`.

1. Model the data relationally: a primary key on every table, foreign keys for every relationship, `NOT NULL` and `CHECK` constraints for invariants, unique constraints for natural keys (for example `(agent_id, idempotency_key)`).
2. Add an index for every foreign key and for every column used in frequent filters or sorts. Do not add indexes "just in case".
3. No duplicate or derivable columns unless justified (for example a denormalized total kept for speed, with a test that it stays consistent).
4. **All schema changes go through Flyway migrations** in `db/migration`. **Never edit an applied migration**; add a new one.
5. **Destructive changes** (drop/rename column or table, type narrowing, data deletion) require explicit owner approval (§L, Step 3).
6. **Money:** `NUMERIC` (or integer minor units) with an explicit currency. Never `double`/`float`.
7. The **ledger is append-only**: no `UPDATE` or `DELETE` on ledger rows, and no repository methods that do it.
8. Credentials and connection strings come from environment variables. Never hardcoded, never committed.
9. Consider data integrity first (constraints, transactions, locking against concurrent overspend), query performance second.

---

## E. Frontend Standards

Details: `AGENTS §21`.

1. Component-based architecture with React + TypeScript in `strict` mode. Small, focused, reusable components (`DataTable`, `StatusBadge`, `BudgetBar`, `ConfirmDialog`).
2. **API calls live in `src/api/`**, never inside UI components. Components call hooks (for example TanStack Query); hooks call the API layer. One central place for base URL, headers, and error mapping.
3. Every data-driven view implements **four states: loading, error, empty, success**. An unhandled rejected promise or a blank screen is a bug.
4. Validate forms on the client for fast feedback **and** on the server for correctness. Never rely on client validation for security or integrity.
5. No duplicated components or duplicated fetches: reuse the cached query instead of calling the same endpoint from several components.
6. Consistent naming: `PascalCase` components, `camelCase` functions and variables, one component per file.
7. Responsive layout, readable contrast, keyboard-accessible controls, and visible focus. Professional, consistent look, using the shared CSS variables.
8. **No hardcoded mock data in production-facing code paths.** Demo data comes from the backend `demo` profile seeder and is clearly labelled as demo data.
9. Never trust the UI for authorization: hiding a button is not access control.

---

## F. API Integration

Canonical contract: `AGENTS §16`. Rules:

1. Keep one consistent response and error structure across all endpoints.
2. Use the correct HTTP method and status code for every operation (see §C.10). `POST /payments` MUST require an `Idempotency-Key` header.
3. Handle API errors gracefully on both sides: the backend returns a structured error; the frontend shows a useful message and a retry path where it makes sense.
4. Centralize API configuration (base URL, timeouts, auth headers) in one place per side. Set explicit **timeouts** on every outbound HTTP call (PayPal, LLM).
5. Do not duplicate API calls or re-implement the same call in two places.
6. **Never return secrets or internal details** (API key hashes, PayPal tokens, stack traces, SQL errors). API keys are shown in plaintext exactly once at creation or rotation.
7. Document important endpoints with OpenAPI (springdoc) and keep `README.md` / `docs/` in sync when the contract changes.

---

## G. Security Standards

Details: `AGENTS §15`. Security is never traded for speed.

1. **No hardcoded secrets** (passwords, tokens, API keys, client secrets). Use environment variables. `.env` is git-ignored; `.env.example` holds placeholders only.
2. Validate all inputs at the boundary (Bean Validation, size limits, allow-lists). Use parameterized queries only. Treat invoice text and all LLM output as **untrusted data**.
3. **Authentication and authorization are enforced on the backend**: stateless API-key auth for agent endpoints, admin authentication for dashboard/admin endpoints, signature verification for PayPal webhooks. An agent key MUST NOT reach any admin endpoint (this is tested).
4. Role/permission checks happen server-side on every protected operation, not only in the UI.
5. Protect against the standard vulnerabilities: SQL injection (parameterized queries), XSS (no `dangerouslySetInnerHTML` with untrusted data), CSRF (enabled on session-authenticated admin routes), broken access control, excessive data exposure, SSRF (never fetch user-supplied URLs), unsafe deserialization.
6. Password and key handling: BCrypt for admin passwords; API keys are random (`SecureRandom`), stored only as a hash, and compared in constant time.
7. **Never log** passwords, tokens, API keys, `Authorization` headers, or personal data. Redaction is covered by a test.
8. Sandbox only. No real PayPal credentials, no live endpoints, no real money. Do not describe the project as production-ready.
9. Run a secret scan before the first push and again before submission. If a secret leaks, **rotate it immediately**; deleting the commit is not enough.

---

## H. Error Handling and Logging

1. One centralized error-handling mechanism (`@RestControllerAdvice`) and one error response format.
2. **No swallowed exceptions. No empty `catch` blocks.** Either handle it meaningfully, wrap and rethrow with context, or let it propagate.
3. Error messages are meaningful to the caller and never expose internals (stack traces, SQL, file paths, secrets).
4. Log levels: `ERROR` = needs attention, `WARN` = unexpected but handled, `INFO` = business events (payment decided, webhook processed), `DEBUG` = diagnostics (off in production). Log once per failure, at the boundary that handles it.
5. Include a **correlation ID** (payment request ID) in logs for every payment flow so one payment can be traced end to end.
6. Never log sensitive data (see §G.7).

---

## I. Testing and Code Quality

Details: `AGENTS §20`.

1. **Unit tests for business logic**, especially: policy engine (every rule, every boundary), payment state machine (every allowed and forbidden transition), idempotency, money arithmetic.
2. **Integration tests for critical workflows** with a real PostgreSQL (Testcontainers) and PayPal stubbed (WireMock): payment pipeline, approval flow, webhook processing, concurrent requests against one budget, auth boundaries.
3. Test edge cases and failures: duplicate idempotency keys, same key with a different body, concurrent spend, PayPal timeout or 5xx, duplicate and invalid-signature webhooks, expired approvals.
4. **Existing tests MUST keep passing.** Never delete, skip, or weaken a test to get a green build. Fix the code, or explain why the test is wrong and get approval.
5. Run the relevant tests and the full build (`./mvnw -B verify`, plus the frontend build and tests once their scripts exist) after every significant change, and before every commit.
6. Add a dependency only when it earns its place (see §K.6). Prefer the JDK and Spring.
7. Code quality gates: compiles without warnings introduced by your change, formatting check passes, no unused code or imports.

---

## J. Git and Project Management

Details: `AGENTS §25`.

1. **Conventional Commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`), small and focused, one logical change each. Messages say *what* and *why*.
2. `main` stays green. Never force-push `main`.
3. **Never commit** secrets, `.env`, credentials, `target/`, `node_modules/`, or other generated artifacts.
4. **Do not overwrite or revert changes you did not make.** If a file has unexpected modifications, stop and ask.
5. Do not modify unrelated files. No drive-by refactors, renames, or reformatting.
6. Keep documentation current when behavior changes: `README.md`, `docs/architecture.md`, `docs/PROGRESS.md`, `.env.example`, OpenAPI.
7. Keep the folder structure clean and as defined in `AGENTS §5`. New top-level folders need a reason.
8. **Submission freeze:** after **Thursday Nov 12, 2026, 12:00 pm PT**, make **no** commits, README edits, or video changes until winners are announced.

---

## K. AI-Assisted Development Rules

Claude (and any other AI assistant) MUST follow these on every task in this project:

1. Read `PROJECT_RULES.md` (and `AGENTS.md`, and `docs/PROGRESS.md` if it exists) before starting development work.
2. Understand the existing implementation before modifying anything.
3. Never assume a file's contents; open and read it.
4. Never rewrite an entire file when a targeted modification is enough.
5. Never delete existing functionality without explicit approval.
6. Never introduce a new dependency without explaining why it is needed (and what was considered instead).
7. Never change configuration, environment, or database settings without explaining the impact first.
8. Never make unrelated changes while implementing a feature.
9. Never present a placeholder or stub as completed functionality. Stubs MUST be labelled as such in code and in the report.
10. Never claim code works without verifying it. If something was not run, say "not run".
11. If something is unclear and the answer could significantly affect the architecture, ask before deciding.
12. If several approaches exist, briefly list the options and recommend the simplest appropriate one.
13. Before modifying files, state the intended changes.
14. After implementing, summarize: files modified, functionality implemented, tests performed with real results.
15. If a requirement conflicts with this file or `AGENTS.md`, highlight the conflict and ask which wins.

**Project-specific additions**
16. Never invent PayPal, Spring AI, or AG Grid API behavior. Read the official docs and check the **installed version**, not memory. If docs are ambiguous, run a sandbox spike and record the finding in `docs/decisions/`.
17. The LLM never holds PayPal credentials and can never override policy. Its output is untrusted input (`AGENTS §1.1`).
18. Anything on the out-of-scope list (`AGENTS §3.2`) is not built without explicit approval.
19. Ask the human only for things only the human can provide (`AGENTS §29`); make and record sensible assumptions for everything else.

---

## L. Development Workflow (mandatory)

### Step 1: Analyze
Read the requirement and the relevant existing files. Restate what must change and how it will be verified.

### Step 2: Plan
List files to create or modify, the approach, any new dependency, and the potential impact (data, security, API contract, other features).

### Step 3: Confirm (only when needed)
Use these tiers so the process stays light:

| Tier | Examples | Action |
|---|---|---|
| **Proceed** | Work inside the current slice; new tests; small refactor within touched code; docs updates | Just do it |
| **Announce, then proceed** | New dependency; new config property or env var; new Flyway migration that only adds tables/columns; new endpoint already in the `AGENTS §16` contract | State it in the plan and continue |
| **STOP and get approval** | Destructive schema change or edit of an applied migration; any auth, security, or money-movement logic change that alters an invariant; deleting functionality or tests; anything on the out-of-scope list; changing a fixed stack decision; changing the API contract; destructive git operations; any conflict between this file and `AGENTS.md` | Explain options and a recommendation, then wait |

### Step 4: Implement
Focused, incremental changes in vertical slices. Follow the standards above. One slice at a time; commit when it is green.

### Step 5: Verify
Compile, run the relevant tests, then the full build. Check for regressions, formatting, and secrets. Do not skip failing steps; report them.

### Step 6: Report
Concise summary with:
- What was implemented
- Files created or modified
- Dependencies added (and why)
- Tests and builds executed, with their **actual** results
- Known limitations, assumptions, and pending tasks

---

## M. Practicality Constraints

- This is a hackathon: **prioritize** working end-to-end functionality, reliability, security, maintainability, and delivery speed, in that spirit.
- Do not add enterprise ceremony. No extra layers, patterns, or documents that do not protect correctness or help the demo.
- **Never compromise security or data integrity for speed.** If time runs short, cut scope (`AGENTS §3.2` cut order), not safety.
- Keep the design explainable in a 3-minute video and a technical Q&A: one diagram, one request flow, a few clear invariants.

---

## N. Definition of Done (every change)

- [ ] Meets the requirement and respects `AGENTS.md` scope
- [ ] Follows sections A to K of this file
- [ ] New logic has tests; edge and failure cases covered
- [ ] Full build passes (`./mvnw -B verify`, plus frontend checks once available), with real output reviewed
- [ ] No secrets, no unrelated changes, no dead code
- [ ] Docs, `.env.example`, and `docs/PROGRESS.md` updated where behavior or config changed
- [ ] Report delivered per Step 6

The full submission checklist lives in `AGENTS §27`.

---

## O. Open items to finalize once the real project exists

1. Confirm actual versions and scripts from `pom.xml` and `package.json` and update §0.4.
2. Add the exact frontend verification commands (lint, type-check, test, build).
3. Confirm the base package / group ID.
4. Decide the LLM provider and model (human task).
5. Align the header of `AGENTS.md` ("single source of truth") with §0.2 of this file.
6. Add module-specific rules discovered during implementation (for example PayPal Payouts quirks found in the sandbox) as short ADRs in `docs/decisions/`.
