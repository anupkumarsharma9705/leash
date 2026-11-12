# 001 - PayPal Payouts is the MVP money rail

- **Status:** accepted; sandbox spike pending owner credentials
- **Date:** 2026-11-10
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)

## Context

Leash needs one real sandbox money movement to prove that the policy decision controls money, not
just a database row. The plausible PayPal rails are:

1. **Payouts** — business account sends money to a payee email; one payout batch can contain one
   item; the payment request is a push, not a checkout.
2. **Orders / Checkout** — a buyer approves a payment in a PayPal-hosted flow. This assumes a human
   buyer and does not fit "an agent pays a supplier from an owner-controlled budget".
3. **Invoicing** — creates a bill for someone else to pay, which is the opposite direction.

The demo scenario is an AI agent paying invoices from a business account to allow-listed payee
emails. Payouts is the only rail whose model matches that scenario and whose API supports an
idempotent, server-to-server submission without a browser.

## Decision

1. **PayPal Payouts (sandbox) is the only money rail in the MVP.** Checkout, Orders, and Invoicing
   are out of scope.
2. One Leash payment maps to exactly one payout item in one batch:
   - `sender_batch_id = "leash-<paymentId>"`
   - `sender_item_id = "<paymentId>"`
   - `recipient_type = EMAIL`
   - header `PayPal-Request-Id: <paymentId>`
3. The payment id is the idempotency key on every retry, so a timeout or crash cannot create a
   second payout.
4. The item transaction status is authoritative for the final payment state. Batch success alone is
   not success. See [`002-payout-status-mapping.md`](002-payout-status-mapping.md).
5. The app refuses to start against a non-sandbox host unless `LEASH_ALLOW_LIVE=true`. The project
   never sets that flag.

## Spike status

The one-time `$1.00` sandbox payout (Gate G1) has **not** been executed because no
`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, or funded sandbox recipient was available. The adapter,
OAuth client, retry policy, status lookup, and status mapping are implemented and covered by
WireMock tests, and a payment with missing credentials safely stays `APPROVED` for the reconciler.
A test double is not evidence that Payouts is enabled on the owner's PayPal app; that remains an
owner task in [`../paypal-sandbox-setup.md`](../paypal-sandbox-setup.md).

If PayPal returns `PERMISSION_DENIED` / `NOT_AUTHORIZED` for Payouts, the response is to resolve the
account entitlement or record a new ADR. It is not a reason to silently switch rails, because that
would change the product's trust model and the approval flow.

## Consequences

- The demo needs a funded PayPal Business sandbox account and a Personal sandbox recipient.
- Payout eligibility is an external risk that can block the money-movement scene; the policy,
  escalation, denial, ledger, and scripted agent scenes still work without credentials.
- Payout webhooks (`PAYMENT.PAYOUTS-ITEM.*`) are the status source of truth; the reconciler polls
  the batch when webhooks are absent.
- Supporting another rail later means a new port and a new status mapper, not a branch inside the
  policy engine.
