# 002 - Payout status mapping

- **Status:** accepted
- **Date:** 2026-11-10
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)

## Context

PayPal Payouts exposes two status strings: the batch status and the per-item transaction status. A batch status of `SUCCESS` only means the batch finished processing; its items can still be `ONHOLD`, `UNCLAIMED`, or `FAILED`. Treating batch `SUCCESS` as payment success could show an unreceived payment as complete, or worse, reconcile the ledger incorrectly.

The OpenAPI contract (`paypal-rest-api-specifications`, `payments_payouts_batch_v1.json`) defines:

- Batch: `DENIED`, `PENDING`, `PROCESSING`, `SUCCESS`, `CANCELED`
- Item: `SUCCESS`, `FAILED`, `PENDING`, `UNCLAIMED`, `RETURNED`, `ONHOLD`, `BLOCKED`, `REFUNDED`, `REVERSED`

## Decision

1. The **item transaction status is the source of truth** for payment state.
2. `PayPalStatusMapper.resolve(batchStatus, itemStatus)` uses the item status whenever it is present.
3. Batch `SUCCESS` maps to `SUBMITTED`, not `SUCCEEDED`.
4. Batch `DENIED` / `CANCELED` map to `FAILED`.
5. Item `SUCCESS` maps to `SUCCEEDED`; item `FAILED`, `BLOCKED`, `RETURNED`, `REFUNDED`, `REVERSED` map to `FAILED`.
6. Item `PENDING`, `UNCLAIMED`, `ONHOLD`, and every unknown status map to `SUBMITTED`, keeping the payment open for the reconciler instead of guessing.
7. Raw PayPal status strings are stored on the payment so the audit trail keeps PayPal's exact vocabulary.

## Consequences

- A payment becomes `SUCCEEDED` only from an item success or a verified item webhook.
- The reconciler must keep polling batches whose item is `ONHOLD` or `UNCLAIMED`.
- New PayPal statuses fail safe (stay pending), never fail open.
- Tests enumerate every documented status plus an unknown status.
