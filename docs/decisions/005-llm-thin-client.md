# 005 - LLM demo mode uses a thin OpenAI-compatible tool-calling client

- **Status:** accepted
- **Date:** 2026-11-27
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)
- **Supersedes:** 003

## Context

`AGENTS.md` §4 and §14 require an optional `LLM` demo mode behind the `AgentModelClient` port. The
fixed stack names Spring AI, with an explicit fallback: a thin client for an OpenAI-compatible
tool-calling API behind the same port, after a time-boxed spike and an ADR. ADR 003 initially
deferred the mode because no provider, model, or key had been chosen.

The demo agent is not part of the trust boundary. It calls Leash only over the public HTTP API with
its own bearer key. The model proposes tool calls; Leash evaluates policy, locks budget, resolves
payment facts, and records the ledger. A model failure must not prevent the application from booting
or the deterministic demo from running.

## Decision

1. Implement `AgentModelClient` with `OpenAiCompatibleAgentModelClient`, a `RestClient` adapter for
   the common `POST {baseUrl}/chat/completions` tool-calling shape used by OpenAI, DeepSeek, and
   compatible gateways. Spring AI is not on the classpath; this is the documented thin-client
   fallback.
2. Configuration is `LlmProperties` (`leash.llm.*`, env `LEASH_LLM_BASE_URL`, `LEASH_LLM_API_KEY`,
   `LEASH_LLM_MODEL`, `LEASH_LLM_TIMEOUT`, `LEASH_LLM_MAX_ITERATIONS`, `LEASH_LLM_TEMPERATURE`). LLM
   mode is enabled only when base URL, key, and model are all non-blank.
3. The model may call only `check_budget` and `pay_invoice`. `pay_invoice` accepts an `invoiceId`
   only; the server resolves payee, amount, currency, and memo from the bundled invoice file. The
   model cannot invent a payment or change its terms.
4. The system prompt treats invoice text and memos as untrusted data, forbids splitting a payment to
   evade a limit, and states that `DENY` and `ESCALATE` are final outcomes to report.
5. With any required value missing, `LLM` fails closed with `501 LLM_NOT_CONFIGURED`; `SCRIPTED`
   remains the default. The application boots and the full suite passes with no key present.
6. Provider errors become `502 LLM_UPSTREAM_FAILED`. The key stays in the adapter, is never logged,
   and is never returned by the status endpoint.

## Consequences

- The demo can use any OpenAI-compatible provider without a code change; the exact runtime model is
  whatever the deployment configures. The repository ships no key.
- The recorded demo remains `SCRIPTED`, so the submission text must not claim a live model run that
  was not recorded.
- Spring AI can replace the adapter later without changing `LlmDemoAgent`, policy, payments, or the
  ledger. Model output remains untrusted input.
- Verified by `LlmDemoAgentTest`, `OpenAiCompatibleAgentModelClientTest`, and
  `AdminDemoApiIntegrationTest` (missing configuration, tool loop, invoice resolution, upstream
  failure, and no-key boot).
