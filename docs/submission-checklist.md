# Submission checklist

Status on **2026-11-12**. Deadline: **18:00 IST**. Frozen after submission.

| # | Item | Status | Evidence / owner action |
|---|---|---|---|
| 1 | Public repository with MIT license visible in About | **Owner action** | `LICENSE` is present; the owner must create the public GitHub repo, push, and set the copyright holder. |
| 2 | Public YouTube video under 3 minutes | **Owner action** | Script and shot list: [`demo-script.md`](demo-script.md). The title, description, and honesty wording are in [`submission-description.md`](submission-description.md). The video must show the working console. |
| 3 | Hosted demo URL or complete run-from-source instructions | **Run-from-source done; hosted blocked** | [`../README.md`](../README.md) §4. The multi-stage image builds and passed a local container smoke test; `render.yaml` is ready. No Render account/URL. |
| 4 | Description names PayPal and the AI model; Built With filled | **Copy ready; owner action** | Use [`submission-description.md`](submission-description.md). Name **PayPal Payouts (sandbox)**. This build calls **no LLM**; the agent is SCRIPTED. Do not claim a model that is not wired. |
| 5 | Sponsor tools ticked | **Owner action** | Tick **AG Grid Community**. Tick **Render** only if the blueprint is actually deployed. |
| 6 | Real sandbox payout and verified webhook shown | **Blocked** | Needs `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, a funded sandbox recipient, and `PAYPAL_WEBHOOK_ID`. WireMock tests are not a substitute. Run [`gate-g1-runbook.md`](gate-g1-runbook.md) before recording. |
| 7 | No API keys committed; secret scan clean | **Done locally** | `.env` is ignored, `.env.example` has placeholders only, and the working-tree scan is clean. `.gstack/` is now fully ignored; it contains a local terminal token. Re-run before the first push; rotate on any hit. |
| 8 | Test credentials provided | **Done** | Local console: `admin` / `admin-test-password` (BCrypt hash in README §4.2). Change for any shared deployment. |
| 9 | Submission frozen after the deadline | **Pending** | No commits, README edits, or video swaps after 18:00 IST until winners are announced. |

## What is verified

- Backend: **364 tests**, 0 failures, 0 errors, 0 skipped; Spotless clean; policy coverage 97.5%.
- Frontend: TypeScript strict build and lint clean.
- Live browser: sign-in, ledger, filters, timeline, approval confirmation and approval, policy save,
  suspend/resume, demo nav, LLM gating, and a scripted run whose repeat replays all three payments.
- Production image: built (`leash:local`, 572 MB) and run against a fresh PostgreSQL database. The
  container served the packaged console and health endpoint, returned `401` for unauthenticated admin
  and agent calls, and completed a CSRF-protected scripted demo whose second run replayed all three
  payments with no budget change.

## What is not verified

- A real PayPal sandbox payout (Gate G1).
- A live PayPal webhook (Gate G4 / Slice 1.6).
- A Render deployment.
- LLM tool calling (Slice 2.9); the route fails closed with `501 LLM_NOT_CONFIGURED`.

These are stated in the README and video script. Do not present them as working.
