<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0039: Server-enforced two-step confirmation for deletes and outward-facing actions

- Status: accepted
- Date: 2026-09-30
- Source: issue #75 (M1-C3); spec §9, §9.1; AGENTS.md §6; threat model T2; refines ADR-0012, ADR-0033

## Context

Deletes and actions that leave the app need the user's confirmation, and the server must enforce it,
also for MCP clients (ADR-0012): a UI dialog alone can be skipped by any other client, and a
prompt-injected AI (T2) can call any tool it sees. Several feature issues (#29, #31, #32, #33, #41,
#117) need the same mechanism, so it has to exist once, in the kernel, before them.

## Decision

### One gate in the application layer

- `ConfirmActionUseCase` (`shared.application`) is the gate. A destructive or outward-facing
  **feature use case** calls it first with a `ConfirmationRequest`: the requester (`Actor` + the
  session or client connection), the `ConfirmableAction` (operation such as `applications.delete`,
  target ids, and an *effect* the feature derives from the current state, e.g. "application 42 with
  3 documents") and the token, if any. It runs its mutation only on `ConfirmationResult.Confirmed` and
  returns every `ConfirmationResult.Unconfirmed` to its caller unchanged. Because the gate sits inside
  the feature use case, the REST controller and the MCP tool call the same code and neither can skip it.
- **First step** (no token): nothing runs. The gate issues a token and answers `Required(token,
  expiresAt, action)`. **Second step** (token): the token is spent (removed) whatever happens next, then
  checked for expiry and for an exact match of actor, session, operation, targets and effect.
  A mismatch, a replay, an unknown or expired token is `Rejected(reason)`; the caller starts over.
- The binding is a SHA-256 digest over the length-prefixed fields (`ConfirmationBinding`, domain),
  compared with `MessageDigest.isEqual` (constant time). The digest is JDK standard library, pure and
  deterministic; the domain still has no I/O, clock or randomness. The effect in the digest means a
  target that changed between the two steps (a document was added) no longer matches what the user saw.
- Tokens: 256 bits from `SecureRandom`, URL-safe Base64, valid for **5 minutes** (long enough to read
  a dialog or answer an MCP elicitation, short enough that a leaked token soon dies), single use.
  `ConfirmationToken.toString()` and `ConfirmationRequester.toString()` are redacted.

### In-memory store

`InMemoryConfirmationStoreAdapter` (`bootstrap`, `ConfirmationStorePort`) keeps pending confirmations
in a map keyed by the SHA-256 of the token, so the raw token exists nowhere on the server and lookup
timing reveals nothing about a valid token. One lock makes issue and redeem atomic (parallel replays:
one wins). It holds at most 1,000 entries; expired ones go first, then the oldest, whose user just
confirms again. Only the `app` container serves requests and a restart only costs a re-confirmation,
so there is no table and nothing for export/import (pending confirmations are ephemeral, like sessions).

### REST convention (`shared.adapter.web.Confirmations`)

| Step | Request | Answer |
|---|---|---|
| 1 | the operation, without `Jofi-Confirmation` | `428 Precondition Required`, `application/problem+json`, type `urn:jofi:problem:shared:confirmation-required`, members `confirmationToken`, `expiresAt`, `operation`, `targets` |
| 2 | the same request with header `Jofi-Confirmation: <token>` | the operation's normal success answer |
| 2, refused | token unknown, used, expired or for another session/operation/target/effect | `412 Precondition Failed`, type `urn:jofi:problem:shared:confirmation-invalid`; start over at step 1 |

- `428` says "this request needs a precondition first" (RFC 6585), `412` "the precondition you sent does
  not hold" (RFC 9110), which is what a stale token is; neither collides with 401/403/409/422.
- The token travels in a **header**, never in the URL, so access logs, browser history and referrers
  never see it; `DELETE` needs no body. Session, CSRF (ADR-0035) and the 401 for no session apply to
  both steps unchanged: the requester is the user plus the session id, and without a session the
  helper raises Spring Security's 401 instead of binding to nothing.
- The token is not a member of the problem's extension map but a property of its own
  (`ConfirmationRequiredProblem`), because Spring logs resolved exceptions and `ProblemDetail.toString()`
  prints that map at debug level. A test with debug logging proves no token reaches the logs.
- Contract: the `ConfirmationRequiredProblem` schema (`allOf` `ProblemDetail`) is in `api/openapi.json`,
  and every operation that declares the `Jofi-Confirmation` header gets a documented `428` response
  (`OpenApiSpecApplication.ContractCustomizer`).
- Frontend: `runConfirmed` (`src/api/confirmation.ts`) runs the flow; `useConfirmation()`
  (`src/app/useConfirmation.tsx`) wires it to `ConfirmDialog` (`src/ui`, an `alertdialog`). The dialog
  text is the feature's own Paraglide message; the server's problem carries ids, not prose, so no
  English server text reaches the UI.

### MCP (later, #117)

Tools use the same feature use cases. The requester is `Actor.ExternalClient(name)` (or `Actor.Ai` for
the built-in chat) with the client token id or MCP session as its session. The token proves a second
deliberate call about exactly this action; it does **not** by itself prove a human saw it, since an AI
client could make both calls. The human-in-the-loop step for AI clients therefore relays the request
to the user (MCP elicitation, the chat's confirmation relay #122) and only then sends the token back.

## Consequences

- Feature issues add no confirmation code of their own: build the `ConfirmableAction`, call the gate,
  map `Unconfirmed` with `Confirmations.problem(...)` in the controller, take the header with
  `@RequestHeader(Confirmations.HEADER, required = false)`, and use `useConfirmation()` in the UI.
- The gate records nothing in the changelog: it mutates no domain data. The confirmed mutation writes
  its entry with its actor as usual (spec §13).
- A restart or a store overflow drops pending confirmations; users see a 412 and confirm again.
- Two app instances would need a shared store; Jofi runs one `app` container, so this stays in memory.
