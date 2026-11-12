# 003 - LLM demo mode is deferred, not faked

- **Status:** accepted
- **Date:** 2026-11-12
- **Deciders:** implementation agent (owner approval pending with AGENTS.md)

## Context

`AGENTS.md` §14 asks for a demo agent with two modes: deterministic `SCRIPTED` and an `LLM` mode
using Spring AI tool calling behind an `AgentModelClient` port. The LLM path needs a provider, model,
base URL, and API key. None was chosen or supplied, and the submission deadline does not allow a
provider spike plus prompt work without risking the verified demo.

The product's trust boundary does not depend on the agent being an LLM. The policy engine, agent key,
budget lock, approval flow, and ledger are identical for a scripted caller. What matters for the
demo is that the agent calls Leash only over the public HTTP API with its own key and cannot bypass
policy.

## Decision

1. `SCRIPTED` is the only working demo mode in this build. It makes fixed tool calls in invoice
   order and is deterministic for the video.
2. `LLM` stays in the API enum and the console, but the route fails closed with
   `501 LLM_NOT_CONFIGURED`. There is no placeholder model, no fake response, and no hidden
   dependency on an external provider.
3. No `AgentModelClient` implementation is added. Slice 2.9 will add the port, a provider adapter,
   the tool loop, and its tests together; an empty interface now would be dead code.
4. The submission must state that no LLM is called. The project is infrastructure *for* AI agents,
   not an LLM application in this build.

## Consequences

- The app boots and the full test suite passes with no model key present.
- The demo video and submission text cannot claim an AI model run. They show a scripted agent making
  real HTTP calls into the same policy gateway.
- Adding an LLM later changes only the demo agent and its adapter. Policy, payments, approvals,
  webhooks, and the ledger must not change; model output remains untrusted input.
- If an LLM key arrives before the deadline, this ADR is superseded only after the path is tested and
  the video is re-recorded against it. A key alone is not a working integration.
