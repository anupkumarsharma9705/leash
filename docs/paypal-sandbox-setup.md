# PayPal sandbox setup (owner guide)

> Human task for Slice 0.1. Do this while the rest of Phase 0 is being scaffolded.
> The app is **sandbox only**. Never put live credentials on this machine.
> PayPal's dashboard changes: if a label differs, follow the current UI, not this text.

## 1. Accounts

1. Sign in at <https://developer.paypal.com/> with an existing PayPal account or create one.
2. Open **Sandbox → Accounts**.
3. Create:
   - **1 Business** account (the payer / merchant that owns the REST app). Fund it with a large sandbox balance.
   - **3 Personal** accounts. Two will be allow-listed payees (the $12 and $150 scenes); the third is the unknown payee for the $900 deny scene.
4. Write down each sandbox email. They are fake and safe to share with the repo as demo data, but keep credentials out of git.

## 2. REST app

1. **Apps & Credentials → Sandbox → Create App**.
2. Type: Merchant. Name: `leash`.
3. Copy **Client ID** and **Secret** into `.env` as `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`.
4. Set `PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com`.

## 3. Payouts check (biggest external risk — Gate G1)

Payouts must be enabled for the business account/app.

1. In the app details, confirm Payouts is listed as an available feature.
2. If a payout later fails with `PERMISSION_DENIED` / `NOT_AUTHORIZED`, do **not** switch APIs. Ask in the PayPal Discord, then record the outcome in `docs/decisions/001-payouts-spike.md`.
3. Slice 1.4 runs one real $1.00 sandbox payout business → personal and polls status. Gate G1 passes only when the money is visible in the personal account. The exact commands and pass criteria are in [`gate-g1-runbook.md`](gate-g1-runbook.md).

## 4. Webhook (Slice 1.6, after the first Render deploy)

1. In the same REST app, **Add Webhook**.
2. URL: `https://<your-service>.onrender.com/webhooks/paypal`.
3. Subscribe to the payout item events. Use the dashboard's event picker to select the exact current names (they start with `PAYMENT.PAYOUTS-ITEM.`); confirm them against the official Payouts webhook docs before implementing the mapper.
4. Copy the **Webhook ID** into `PAYPAL_WEBHOOK_ID` (local `.env` and Render env).
5. Verify with the dashboard's test event and watch the app log for a verified, stored event.

## 5. Verification checklist

- [ ] OAuth token request returns a token (Slice 1.2 test or a manual curl).
- [ ] One $1.00 payout succeeds and appears in the personal sandbox account.
- [ ] Status lookup returns the same state the dashboard shows.
- [ ] A test webhook verifies and is stored exactly once.
- [ ] No live credentials anywhere; `.env` is untracked.

## 6. Never

- Never commit `.env` or paste secrets into chat, docs, or the video.
- Never point `PAYPAL_BASE_URL` at a live host. The app refuses to boot against a non-sandbox host unless `LEASH_ALLOW_LIVE=true` (do not set it).
- Never describe the project as production-ready.
