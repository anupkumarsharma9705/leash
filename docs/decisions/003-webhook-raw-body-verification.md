# 003 - Webhook verification uses the raw request bytes

- **Status:** accepted
- **Date:** 2026-11-11
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)

## Context

PayPal signs the exact JSON body it sends. Any parse-and-reserialize step can change key order,
whitespace, number representation, or string escaping, which changes the bytes and therefore
invalidates a legitimate signature. `verify-webhook-signature` also accepts the event as a
`webhook_event` object, so the payload must be embedded without an object round-trip.

## Decision

1. `POST /webhooks/paypal` reads the request body as bytes directly from the servlet input stream.
   Spring MVC message converters never parse it first.
2. `PayPalWebhookVerifierAdapter` assembles the verification request as bytes:
   fixed JSON fields are serialized, then the original event bytes are appended after
   `"webhook_event":` verbatim.
3. The event envelope (`id`, `event_type`) is parsed only after PayPal returns
   `verification_status = SUCCESS`.
4. An unverified request returns `400` and persists nothing.
5. `webhook_events.event_id` is the dedupe key. The raw event is inserted before application.
   A processed duplicate returns `200` without applying the event again. An unprocessed duplicate
   is retried, because the first application may have failed after the insert.
6. The raw event is stored as `JSONB` for audit. Verification still uses the original bytes, not
   the JSONB representation.

## Consequences

- Signature verification is call-bound to PayPal and needs `PAYPAL_WEBHOOK_ID`.
- A PayPal verification outage returns `503`, allowing PayPal to retry rather than accepting an
  unverified event.
- The body is capped at 1 MiB.
- Tests assert the exact verification request body, including the untouched event text.

## Payout event names

PayPal's current event-name reference lists these Payouts events:

- Batch: `PAYMENT.PAYOUTSBATCH.DENIED`, `PAYMENT.PAYOUTSBATCH.PROCESSING`,
  `PAYMENT.PAYOUTSBATCH.SUCCESS`
- Item: `PAYMENT.PAYOUTS-ITEM.BLOCKED`, `.CANCELED`, `.FAILED`, `.HELD`, `.REFUNDED`,
  `.RETURNED`, `.SUCCEEDED`, `.UNCLAIMED`

Source: <https://developer.paypal.com/api/rest/webhooks/event-names/> (read 2026-11-11).
