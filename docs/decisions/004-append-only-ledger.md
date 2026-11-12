# 004 - Append-only ledger without foreign keys

- **Status:** accepted
- **Date:** 2026-11-11
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)

## Context

`ledger_events` is the audit trail for every business fact: payment requests, decisions, approvals,
PayPal submissions, status changes, policy changes, and key rotation. `AGENTS.md` 13 requires the
table to be append-only, enforced by a database trigger that rejects `UPDATE` and `DELETE`.

The table carries `agent_id` and a nullable `payment_id`. The usual reflex is to add foreign keys.
That conflicts with the purpose of an immutable audit log:

- `ON DELETE CASCADE` would erase history when an agent or payment is removed.
- `ON DELETE RESTRICT` would make an agent undeletable forever once it had any activity, and would
  block test cleanup and future retention work.
- The `payment_requests` table does not exist until Slice 2.5, so a payment foreign key cannot be
  added in this migration anyway.

## Decision

1. `ledger_events` has **no foreign keys** to `agents` or `payment_requests`. The identifiers are
   recorded as immutable values.
2. The table is indexed by `(agent_id, created_at DESC)`, `payment_id`, and `created_at DESC` so the
   dashboard timeline and audit queries stay fast.
3. Database triggers reject `UPDATE`, `DELETE`, and `TRUNCATE`. The trigger raises
   `integrity_constraint_violation` with a message containing "append-only".
4. `LedgerService` uses `Propagation.MANDATORY`: a ledger event must join the caller's transaction,
   so a business change and its audit fact commit or roll back together. Calling it without a
   transaction fails immediately.
5. `LedgerPayloadGuard` rejects forbidden field names (tokens, secrets, passwords, hashes) and
   values that look like agent API keys, bearer tokens, or private keys. The error message names the
   offending path but never echoes the value.
6. The Java persistence layer exposes only `insert` and `findByPaymentId`. There is no update or
   delete method to misuse.

## Consequences

- Deleting an agent or a payment never destroys or blocks its audit history.
- Referential integrity is enforced by the application, not the database. A malformed `agent_id`
  could be inserted by direct SQL; application code always supplies the authenticated agent id.
- The dashboard must tolerate an agent that no longer exists (show the stored id rather than fail).
- Test cleanup can delete agents without touching ledger rows.
- A future retention or archival job must use partitioning or a privileged maintenance path; normal
  `DELETE` and `TRUNCATE` remain impossible.
- Add a database foreign key later only if the product decides that ledger rows may be deleted with
  their owner, which would contradict the append-only requirement.
