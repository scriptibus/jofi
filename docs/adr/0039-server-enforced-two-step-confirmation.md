<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0039: Server-enforced two-step confirmation for deletes and outward-facing actions

- Status: accepted
- Date: 2026-09-30
- Source: issue #75 (M1-C3), its security review; spec §9, §9.1; AGENTS.md §6; threat model T2;
  refines ADR-0012, ADR-0033

## Context

Deletes and actions that leave the app need the user's confirmation, and the server must enforce it,
also for MCP clients (ADR-0012): a UI dialog alone can be skipped by any other client, and a
prompt-injected AI (T2) can call any tool it sees. Several feature issues (#29, #31, #32, #33, #41,
#117) need the same mechanism, so it has to exist once, in the kernel, before them, and it has to be
enforced by the compiler and the architecture tests, not by convention.

## Decision

### One gate in the application layer

- `ConfirmActionUseCase` (`shared.application`) is the gate. A destructive or outward-facing
  **feature use case** calls it first with a `ConfirmationRequest`: the requester (`Actor` + the
  session or client connection), the `ConfirmableAction` and the token, if any. It mutates only with
  the `ConfirmationResult.Confirmed` it gets back and returns every `ConfirmationResult.Unconfirmed` to
  its caller unchanged. Because the gate sits inside the feature use case, the REST controller and the
  MCP tool call the same code and neither can skip it.
- `ConfirmableAction` = operation (`<context>.<verb>`, e.g. `applications.delete`), **targets** and a
  structured **effect** `ConfirmationEffect(kind, name, counts)` (e.g. `application`, "ACME – Backend",
  `documents: 3`).
  - Targets are concrete ids the server resolved, never filters or queries ("all rejected
    applications" is resolved to its ids first). The constructor sorts and de-duplicates them, so the
    same set always binds the same way.
  - The effect is derived **from the same read the mutation acts on, inside the same
    `TransactionPort.inTransaction`** as the mutation: read the targets, build the action, call the
    gate, and on `Confirmed` mutate in that transaction. Computing the effect from one read and
    deleting based on another would let the two drift.
- **First step** (no token): nothing runs. The gate issues a token and answers `Required(token,
  expiresAt, action)`. **Second step** (token): the token is spent (removed) whatever happens next, then
  checked for expiry and for an exact match. A mismatch, a replay, an unknown or expired token is
  `Rejected(reason)`; the caller starts over.
- The binding is a SHA-256 digest over the length-prefixed actor, session, operation, targets and
  effect fields (`ConfirmationBinding`, domain), compared with `MessageDigest.isEqual` (constant time).
  The digest is JDK standard library, pure and deterministic; the domain still has no I/O, clock or
  randomness. **What the binding protects:** the second step executes only for the same actor and
  session, the same operation, the same target set and an effect that the server, re-deriving it at
  the second step, finds identical to the one it issued the token for. So a token cannot be replayed,
  moved to another target or operation, or used from another session, and a change between the steps
  is caught **if and only if it changes a field of the effect** (a document added changes `counts`; an
  edit to a field the effect does not include is not caught). The binding does **not** prove what the
  user saw: the client renders the effect, which is why the frontend checks operation and targets
  against what it meant to run before showing anything, and renders the dialog from the server's effect.
- Tokens: 256 bits from `SecureRandom`, URL-safe Base64, valid for **5 minutes** (long enough to read
  a dialog or answer an MCP elicitation, short enough that a leaked token soon dies), single use.
  `ConfirmationToken.toString()` and `ConfirmationRequester.toString()` are redacted.

### Enforced in code

- `ConfirmationResult.Confirmed` carries the confirmed `ConfirmableAction` and has an **internal**
  constructor: only the domain module can create one, and there only `PendingConfirmation.check` does.
  `ConfirmationRules.onlyTheGateMintsConfirmations` (architecture test) fails if anything but
  `ConfirmActionUseCase` creates or checks a `PendingConfirmation` or constructs a `Confirmed`.
- Destructive and outward-facing **port methods take the proof**: `fun delete(id: ApplicationId,
  proof: ConfirmationResult.Confirmed)`; the adapter checks `proof.covers(operation, id)` before acting.
  A use case therefore cannot call them without having passed the gate.
- `ConfirmationRules.destructivePortCallsPassTheGate`: every use case that calls a port method named
  `delete*`, `remove*`, `send*` or `purge*` either takes `ConfirmActionUseCase` or calls a port method
  that requires `Confirmed`. Exceptions need a reviewed entry with a reason
  (`USE_CASES_WITHOUT_CONFIRMATION`; today `ResetPasswordUseCase`, the operator's startup reset, and
  `CleanUpExpiredSessionsUseCase`, the session housekeeping job).
- `ConfirmationRules.destructiveEndpointsTakeTheConfirmationHeader`: every `DELETE` handler, and every
  handler listed in `OUTWARD_FACING_ENDPOINTS`, has a `@RequestHeader(Confirmations.HEADER)` parameter;
  exceptions (e.g. a logout, were it a `DELETE`) go to `ENDPOINTS_WITHOUT_CONFIRMATION` with a reason.
- Known-bad fixtures (a delete without the header, a `@RequestMapping(method = DELETE)` without it, an
  undeclared outward endpoint, a use case deleting without the gate, a forged confirmation) must be
  rejected (`ConfirmationRulesTest`).

### In-memory store

`InMemoryConfirmationStoreAdapter` (`bootstrap`, `ConfirmationStorePort`) keeps pending confirmations
(only the digest and the expiry) in a map keyed by the SHA-256 of the token, so neither the raw token
nor the session id or action details exist on the server, and lookup timing reveals nothing about a
valid token. One lock makes issue and redeem atomic (parallel replays: one wins). It holds at most
1,000 entries; expired ones go first, then the oldest, whose user just confirms again. Only the `app`
container serves requests and a restart only costs a re-confirmation, so there is no table and nothing
for export/import (pending confirmations are ephemeral, like sessions).

### REST convention (`shared.adapter.web.Confirmations`)

| Step | Request | Answer |
|---|---|---|
| 1 | the operation, without `Jofi-Confirmation` | `428 Precondition Required`, `application/problem+json`, type `urn:jofi:problem:shared:confirmation-required`, members `confirmationToken`, `expiresAt`, `operation`, `targets`, `effect` (`kind`, `name`, `counts`) |
| 2 | the same request with header `Jofi-Confirmation: <token>` | the operation's normal success answer |
| 2, refused | token unknown, used, expired or for another session/operation/target/effect | `412 Precondition Failed`, type `urn:jofi:problem:shared:confirmation-invalid`; start over at step 1 |

- `428` says "this request needs a precondition first" (RFC 6585), `412` "the precondition you sent does
  not hold" (RFC 9110), which is what a stale token is; neither collides with 401/403/409/422.
- The token travels in a **header**, never in the URL, so access logs, browser history and referrers
  never see it; `DELETE` needs no body. Session, CSRF (ADR-0035) and the 401 for no session apply to
  both steps unchanged: the requester is the user plus the session id, and without a session the
  helper raises Spring Security's 401 instead of binding to nothing.
- Token and effect are not members of the problem's extension map but properties of their own
  (`ConfirmationRequiredProblem`), because Spring logs resolved exceptions and `ProblemDetail.toString()`
  prints that map at debug level; the effect's name is personal data. A test with debug logging proves
  no token reaches the logs.
- Contract: the `ConfirmationRequiredProblem` schema (`allOf` `ProblemDetail`) is in `api/openapi.json`,
  and every operation that declares the `Jofi-Confirmation` header gets a documented `428` response
  (`OpenApiSpecApplication.ContractCustomizer`).
- Frontend: `runConfirmed` (`src/api/confirmation.ts`) takes the `expect`ed operation and targets and
  throws `ConfirmationMismatchError` **without asking** if the 428 names anything else. `useConfirmation()`
  (`src/app/useConfirmation.tsx`) renders the dialog from the server's effect through the feature's
  own Paraglide message (`describe(effect)`), so the dialog shows what the server would run, in the
  user's language; the server sends ids and counts, never prose. A newer question cancels an older
  one and unmounting cancels a pending one, so no call hangs. `ConfirmDialog` (`src/ui`, an
  `alertdialog`) keeps Confirm disabled for 300 ms after opening, so the tap that opened it cannot
  confirm it.

### MCP and the built-in chat (#117; the chat relay follows with #122)

Tools use the same feature use cases. The requester is `Actor.Ai` (the built-in chat and today's session
callers) with the MCP session id as its session; `Actor.ExternalClient(name)` with the client token id follows
with #125. A token only proves a second call about exactly this action, not that a human decided: an AI that holds
the token can make both calls. So the confirmation goes to the **human channel** and the model never receives the
token or decides to send it back. Decided in #117:

- A delete tool runs the use case once without a token (nothing mutates), then asks the client through MCP
  elicitation (form mode) and repeats the call with the token held inside the server only on an `accept` with the
  box checked. There is no token-returning two-call fallback: a client without form elicitation cannot delete over
  MCP (`confirmation-unavailable`) and no token is issued for it. MCP contract criterion: a destructive tool
  without a client that can confirm mutates nothing.
- The server guarantees that it asked through the client and acts only on an explicit yes; it cannot prove that a
  person answered. A client that answers `accept` by itself deletes. With the session cookie that is no new power
  (REST deletes are open to the same client); with per-client bearer tokens (#125) it is, so #125 needs its own
  scope or UI confirmation for deletes.
- The elicitation text is built by the server from the structured effect, in English; the stored name is
  neutralised and on a line of its own, labelled as stored text, because titles and names may come from
  postings. The text passes the result filter (ADR-0053) and fails closed. **Exception, decided by Lucas on
  2026-10-01:** this text is English prose from the server, an explicit exception to "structured effects, never
  prose" above; it stays for now. #122 (the chat relay) carries the structured effect alongside the text so the UI
  can render it in the user's language.
- The call waits up to `jofi.mcp.confirmation-timeout` (4.5 minutes) for the answer, so a person has time to read;
  a waiting call holds a thread, so one confirmation per MCP session and `jofi.mcp.max-pending-confirmations`
  (default 4) in all may wait; the slot is taken before the first step, so a refused call issues no token. The SDK
  does not report closed sessions, so a slot frees when the wait ends. A cap per requester in the confirmation store
  itself is #214. The stored name is filtered as stored, before it is neutralised or cut.
- Decided by Lucas on 2026-10-01: a client without form elicitation cannot delete over MCP (no token-returning
  fallback; spec §9.1 is corrected). If a client gives up on the tool call before the timeout but the user accepts
  later within it, the delete still happens: the user did confirm, and the model saw a failed call. A client should
  re-read the entity before retrying a failed or timed-out delete call. The store cap (#214) and a write budget
  (#217) come before external clients (#125).

## Consequences

- Feature issues add no confirmation code of their own: read the targets and derive the effect in the
  mutation's transaction, call the gate, pass `Confirmed` to the port, map `Unconfirmed` with
  `Confirmations.problem(...)` in the controller, take the header with
  `@RequestHeader(Confirmations.HEADER, required = false)`, and use `useConfirmation()` in the UI.
- The gate records nothing in the changelog: it mutates no domain data. The confirmed mutation writes
  its entry with its actor as usual (spec §13).
- A restart or a store overflow drops pending confirmations; users see a 412 and confirm again.
- Two app instances would need a shared store; Jofi runs one `app` container, so this stays in memory.
- `architecture-tests` compiles against `spring-web` (already on its runtime classpath) for the
  controller fixtures.
