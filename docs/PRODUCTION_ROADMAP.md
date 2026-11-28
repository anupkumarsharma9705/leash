# Leash Production Roadmap

Status: **Phase A implemented; updated 2026-11-27.** A1 and A2 are verified, including the GitHub
Packages publish. A3 is verified locally; only the public Render deploy remains an owner step.

> This roadmap continues after the hackathon submission. The hackathon scope is frozen in
> `AGENTS.md`; nothing here changes the sandbox-only demo.

## Strategy: A → C → B

| Phase | What ships | Why it comes first | Stop condition |
|---|---|---|---|
| **A** | `leash-core` library + hosted demo | Proves the spend-control rules are reusable and gives the project a live artifact | Library consumed by one external project |
| **C** | Cross-provider sidecar + framework SDKs | A sidecar is provider-neutral and works with any agent framework; this is the adoption wedge | C4 demand gates fail → stop, keep the portfolio piece |
| **B** | Multi-tenant platform | Only worth the compliance and billing cost after external demand is measured | C4 gates pass |

Anti-deviation rules:

1. No Phase B work before `docs/DECISION_C4.md` records **PROCEED TO B**.
2. `leash-core` imports no Spring, Jakarta, JDBC, or provider SDK classes.
3. PayPal remains sandbox-only until a live-mode ADR is approved.
4. Every phase ends with a git tag and a passing gate script; a failing gate blocks the next phase.
5. New provider code lives in the sidecar, never in `leash-core`.

---

## Phase A — Library + hosted demo

### A1. Extract `leash-core` (verified)

**Prerequisites**

- `main` clean; `./mvnw -B verify` green; Java 21; Docker running.
- `com.leash.policy`, `com.leash.common.money`, `com.leash.payment.domain` have no Spring imports
  (they already were pure Java).
- PayPal port and value types identified: `PayPalPayoutsClient`, `PayoutRequest`,
  `PayoutSubmission`, `PayPalPayoutStatus`, `PayPalStatusMapper`, `PayPalAmountFormatter`,
  webhook verifier port, exceptions, `SandboxGuard`, `Sleeper`.

**Work**

- Maven reactor at `backend/`:
  - `leash-core` — pure Java 21 library, JUnit/AssertJ tests only.
  - `leash-backend` — Spring Boot service, depends on `leash-core`.
- Policy, money, payment-domain, and provider-port packages move to core unchanged (same package
  names, so the service imports do not churn).
- App keeps every adapter, controller, JPA repository, and integration test.
- Coverage rule moves with the policy package: 90% line coverage enforced in `leash-core`.
- Dockerfile and CI build the reactor, not a single module.

**Done means**

- `scripts/gates/verify_a1.sh` passes.
- Core has 184 unit tests; the app keeps its integration suite.
- `leash-core-1.0.0-SNAPSHOT.jar` and `-sources.jar` build.
- Tag `phase-a1-done`.

**Completed version**: `leash-core:1.0.0-SNAPSHOT`, `leash-backend:1.0.0-SNAPSHOT`.

### A2. Publish `leash-core`

**Prerequisites**

- A1 tag exists.
- GitHub repository `anupkumarsharma9705/leash`.
- Workflow `Publish leash-core` can run with `packages: write` (the default `GITHUB_TOKEN` is
  enough).

**Work**

- `distributionManagement` points at `https://maven.pkg.github.com/anupkumarsharma9705/leash`.
- `.github/workflows/publish-core.yml` verifies and deploys on tags matching `v*-core`.
- Sources jar is attached; consumers get readable sources.
- A clean consumer project resolves `com.leash:leash-core:1.0.0-SNAPSHOT` with no source tree.

**Done means**

- `scripts/gates/verify_a2.sh` exits 0 (remote check needs `GITHUB_TOKEN`).
- `mvn dependency:get` works in a fresh project.
- Tag `v1.0.0-core`, then `phase-a2-done`.

**Completed version**: `leash-core:1.0.0` (GitHub Packages).

### A3. Hosted demo

**Prerequisites**

- A2 package is available.
- Render account (or any Docker host) and a managed PostgreSQL 16.
- Admin BCrypt hash, PayPal sandbox credentials, webhook id as secret env vars.

**Work**

- Production config: `${PORT}`, managed Postgres via `DB_*`, graceful shutdown, readiness/liveness
  probes, and a Prometheus scrape endpoint behind admin auth. Health stays public for Render;
  metrics do not.
- Render blueprint at `render.yaml`: Docker web service + Postgres, secrets marked `sync: false`.
- Demo profile seeds the agent and the three invoices; SCRIPTED mode is deterministic.
- The Docker image builds both Maven modules and bakes the React console into the jar.

**Done means**

- `LEASH_DEMO_URL=https://<service> scripts/gates/verify_a3.sh` exits 0.
- Health and readiness are `UP`; Prometheus exposes JVM + HTTP series to an authenticated admin.
- Three demo scenes return; the $12 scene reaches `SUCCEEDED` with sandbox credentials.
- External clean-clone run takes under 10 minutes.
- Tag `phase-a3-done`.

**Completed version**: `leash-backend:1.0.0` deployed.

---

## Phase C — Cross-provider sidecar + SDKs

### C1. Sidecar service

- New module `backend/leash-sidecar` on port 8081.
- Endpoints: `POST /pay`, `GET /payments/{id}`, `GET /health`, provider webhook routes.
- Provider registry: `paypal` implemented by wrapping the existing adapter; unknown providers
  return `501 NOT_IMPLEMENTED`.
- Auth: per-tenant API key; idempotency key required; same state machine as core.
- Docker image under 100 MB (JRE + layered jar); no frontend.
- **Gate**: `scripts/gates/verify_c1.sh`; tag `phase-c1-done`; version `1.0.0`.

### C2. Stripe adapter

- Stripe SDK behind a `PaymentProvider` port; Stripe test keys only.
- Webhook signature verification and replay safety.
- **Gate**: `scripts/gates/verify_c2.sh`; tag `phase-c2-done`; version `1.1.0`.

### C3. Framework SDKs

- Python: `leash-client`, `leash-langchain`, `leash-crewai`, `leash-autogen`.
- TypeScript: `@leash/client`, `@leash/vercel-ai`, `@leash/langchain`.
- Go: `github.com/leash/go-sdk`.
- Each SDK ships a three-scene example that runs against the sidecar.
- **Gate**: `scripts/gates/verify_c3.sh`; tag `phase-c3-done`.

### C4. Adoption decision

Measure stars, PyPI/npm downloads, external issues, and framework examples. Write
`docs/DECISION_C4.md`.

Proceed to B only if all are true:

- 100+ GitHub stars
- 500+ combined package downloads in 30 days
- 10+ external issues/PRs from 5+ people
- 3+ working framework integrations
- 3+ enterprise-demand signals (SSO, billing, audit, multi-tenant)

Otherwise stop at C. **Gate**: `scripts/gates/verify_c4.sh`; tag `phase-c4-done`.

---

## Phase B — Platform (only after C4 passes)

| Step | Scope | Version |
|---|---|---|
| B1 | Multi-org, JWT, MFA, RBAC, data migration | `leash-platform:1.0.0` |
| B2 | Stripe Billing, plans, usage metering | `leash-platform:1.1.0` |
| B3 | Audit export, GDPR delete/export, retention, SOC 2 evidence | `leash-platform:1.2.0` |
| B4 | Partner approvals, Kubernetes HA, load/chaos/restore/pen test | `leash-platform:1.0.0` launch |

Launch requires: PayPal partner approval, Stripe platform approval, p99 < 200 ms at 1000 req/s,
chaos recovery < 30 s, RPO < 1 h / RTO < 4 h, SOC 2 Type I, pen test with zero high findings,
10+ runbooks. Tag `v1.0.0-launch`.
