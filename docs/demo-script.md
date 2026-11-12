# Demo video runbook

Target: **under 3:00**, public YouTube, no third-party trademarks or music. Record in one take if
possible; keep the console zoomed so the amounts and reasons are readable.

## Preflight

1. `docker compose up -d`
2. Terminal A, from `backend/`:
   ```powershell
   $env:SPRING_PROFILES_ACTIVE='local,demo'
   $env:LEASH_RECONCILER_ENABLED='false'
   .\mvnw.cmd spring-boot:run
   ```
3. Terminal B, from `frontend/`: `npm run dev`
4. Open <http://localhost:5173>, sign in as `admin` / `admin-test-password`.
5. Close every other tab, hide the bookmarks bar, and set the browser zoom to 110–125%.
6. Do **not** show `.env`, request headers, the plaintext agent key, or any token.

Optional clean take: stop the backend, reset the local schema, and restart. This clears prior probe
rows so the ledger contains only the three demo payments:

```bash
docker exec leash-postgres psql -U leash -d leash \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

## Shot list

### 0:00–0:15 — The problem

Screen: a simple slide or the README architecture diagram.

> "Companies won't give an AI agent an open wallet. Prompt injection, a bad invoice, or a retry loop
> is enough. Leash sits between the agent and PayPal and makes the policy decision outside the
> agent."

### 0:15–0:45 — Auto-approve

Screen: **Demo**, SCRIPTED selected, click **Run the demo**.

Point at the `$12.00` card: `APPROVE → APPROVED`, payment id, and the before/after budget bars.

> "The agent calls the public payments API with its own key. Twelve dollars is inside policy and
> under the fifty-dollar threshold, so Leash approves it. With sandbox credentials, this is the
> payout submission and the webhook moves it forward."

If credentials are configured, switch to **Ledger** and show the row move from `SUBMITTED` to
`SUCCEEDED`, then open the timeline and show `PAYPAL_STATUS_CHANGED`.

### 0:45–1:15 — Deny

Point at the `$900.00` card: `DENY / PAYEE_NOT_ALLOWED`.

> "Mallory is not on the allow-list. No money moves, and the reason is returned to the agent and
> written to the ledger. The agent cannot edit the policy, and the model cannot talk its way past
> it."

Optionally open the ledger row's timeline to show the decision payload.

### 1:15–1:55 — Escalate and approve

Open **Approvals**. Click **Approve payment** on the `$150.00` card, show the confirmation naming
payee and amount, then click **Approve and pay**.

> "One hundred fifty dollars is legal, but above the threshold, so it waits for a human. Approving
> re-runs the whole policy against the current budget including this reservation. Only then does
> Leash submit. Rejecting would release the reservation instead."

If credentials are configured, show the approved payment moving to `SUBMITTED`. Without credentials,
state plainly that it stays `APPROVED` and the reconciler will submit it when credentials exist.

### 1:55–2:25 — Audit trail and kill switch

Open **Ledger**, click a row, and show the append-only timeline: request, decision, approval,
submission/status.

> "Every fact is append-only, enforced by a database trigger. The timeline is the audit trail."

Open **Agents**, click **Suspend**, confirm, then **Resume**.

> "The kill switch denies the next decision immediately. A payout already at PayPal is not
> recalled."

Show the budget bar (spent + reserved vs the weekly limit).

### 2:25–2:55 — Close

> "Leash is sandbox-only, USD-only, and not production-ready. It is a working control plane:
> PayPal Payouts behind one port, a pure policy engine, idempotent payments, a reconciler, and an
> AG Grid operator console. The next step is more rails and multi-agent controls."

Name the sponsor tools you actually used: PayPal Payouts sandbox, AG Grid Community, and Render if
the deployment is live. Do not claim an LLM run: this build is SCRIPTED.

## Recording checklist

- [ ] Public YouTube upload, under 3:00
- [ ] Shows the working console, not slides only
- [ ] Shows one approval, one denial, one escalation
- [ ] Shows the ledger timeline and the kill switch
- [ ] No secrets, tokens, `.env`, or real personal data
- [ ] No copyrighted music or third-party trademarks
- [ ] Description names PayPal Payouts and states that the demo agent is scripted
