# Phase Gate Checklist

Run from the repository root.

| Shell | Command |
|---|---|
| Linux / macOS / Git Bash | `scripts/verify.sh all` |
| Windows (Git Bash) | `"C:\Program Files\Git\bin\bash.exe" scripts/verify.sh all` |
| One gate | `scripts/verify.sh a1` (also `a2`, `a3`) |

Exit codes: `0` pass, `2` passed locally but an external account step is pending, `1` fail.

## Phase A

### A1 — Core extraction

| Checkpoint | Command / evidence | Status |
|---|---|---|
| PREREQ: clean tree | `git status --short` | done |
| PREREQ: baseline green | `cd backend && ./mvnw -B verify` | done |
| PREREQ: pure candidate packages | no Spring imports in policy/money/payment domain | done |
| MID: reactor modules | `grep '<module>' backend/pom.xml` → `leash-core`, `leash-backend` | done |
| MID: core compiles alone | `./mvnw -B -pl leash-core test` → 184 tests | done |
| COMPLETION: purity | `grep -RE "import (org\.springframework\|jakarta\.)" backend/leash-core/src/main` → empty | done |
| COMPLETION: full verify | `cd backend && ./mvnw -B verify` → BUILD SUCCESS | done |
| COMPLETION: jars | core jar + sources jar + backend jar exist | done |
| COMPLETION: Docker build | `docker build -t leash-demo:local .` | pending |
| COMPLETION: tag | `git tag phase-a1-done` | pending |

Gate script: `scripts/gates/verify_a1.sh`.

### A2 — Publish

| Checkpoint | Command / evidence | Status |
|---|---|---|
| PREREQ: A1 tag + green core | `git tag -l phase-a1-done` | pending |
| MID: distribution block | `grep maven.pkg.github.com backend/leash-core/pom.xml` | done |
| MID: workflow present | `.github/workflows/publish-core.yml` with `packages: write` | done |
| COMPLETION: local install | `./mvnw -B -pl leash-core -am install` | done |
| COMPLETION: clean consumer resolves | `scripts/gates/verify_a2.sh` local half | done |
| COMPLETION: package served by GitHub | `curl -u user:$GITHUB_TOKEN …/maven-metadata.xml` → 200 | pending token |
| COMPLETION: tags | `v1.0.0-core`, `phase-a2-done` | pending |

Gate script: `scripts/gates/verify_a2.sh`.

### A3 — Hosted demo

| Checkpoint | Command / evidence | Status |
|---|---|---|
| PREREQ: A2 package available | GitHub Packages returns 200 | pending |
| MID: production config | `PORT`, `DB_*`, graceful shutdown, `/actuator/health` | done |
| MID: metrics | `/actuator/prometheus` exposes JVM + HTTP series | done locally |
| COMPLETION: health/readiness | local jar: both `UP` | done |
| COMPLETION: SCRIPTED demo | three scenes, login + CSRF | pending password check |
| COMPLETION: live URL | `LEASH_DEMO_URL=… scripts/gates/verify_a3.sh` | pending Render |
| COMPLETION: external clean clone | README only, under 10 minutes | pending |
| COMPLETION: tag | `phase-a3-done` | pending |

Gate script: `scripts/gates/verify_a3.sh`. Without `LEASH_DEMO_URL` it boots the production jar
against local PostgreSQL and exits `2` because "public deploy" is not something the script can
prove.

## Phases C and B

| Gate | Proves | Script |
|---|---|---|
| C1 | Sidecar runs, PayPal works, Stripe returns 501, image < 100 MB | `verify_c1.sh` |
| C2 | Stripe test payment, idempotent replay, verified webhook | `verify_c2.sh` |
| C3 | Python/TS/Go packages install and example agents pass three scenes | `verify_c3.sh` |
| C4 | Adoption metrics meet the published thresholds; decision written | `verify_c4.sh` |
| B1 | Org isolation, JWT, MFA, RBAC, migration | `verify_b1.sh` |
| B2 | Checkout → subscription → entitlement → downgrade | `verify_b2.sh` |
| B3 | Audit export, GDPR delete/export, retention, SOC 2 evidence | `verify_b3.sh` |
| B4 | Partner approvals, load/chaos/restore, pen test, launch tag | `verify_b4.sh` |

Those scripts are created when the phase starts. The gate names and pass criteria are frozen in
`docs/PRODUCTION_ROADMAP.md` so implementation cannot drift.
