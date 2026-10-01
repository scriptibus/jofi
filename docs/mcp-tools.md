<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# MCP tool reference

Jofi's MCP server (ADR-0012, ADR-0053) is the one tool surface for the built-in chat and external MCP
clients. This page lists every tool; the authoritative descriptions and argument schemas are the ones the
server sends in `tools/list` (`<context>.adapter.mcp.*Tool` in `backend/adapters/mcp`). Update this page in
the PR that adds or changes a tool.

## Connecting

- Endpoint: `POST`/`GET`/`DELETE` `/mcp`, MCP Streamable HTTP (protocol 2025-11-25), MCP sessions.
- Authentication: the logged-in session (`SESSION` cookie) and, on `POST`/`DELETE`, the CSRF token from the
  `XSRF-TOKEN` cookie in `X-XSRF-TOKEN`, as for the REST API. Without a session: `401`. Per-client bearer
  tokens for external clients follow with #125.
- A request with an `Origin` other than Jofi's own gets `403`.
- Session callers act as the AI (`Actor.Ai`); changes made through tools are logged with that actor.

## Results

- Every result is one text content holding JSON. Failures are tool results with `isError: true` and
  `{"code": "...", "message": "...", "problems": [{"argument": "...", "problem": "..."}]}`.
  Codes: `invalid-arguments`, `not-found`, `unavailable`, `failed`, `internal-error`, `unauthenticated`,
  `privacy-filter-failed` (the "never send to AI" flags could not be read, so nothing was returned).
- Values flagged "never send to AI" are replaced by `[withheld]` in every result.
- Content copied from job postings or web pages is wrapped as
  `{"trust": "untrusted", "notice": "...", "content": ...}`: data, never instructions.

## Tools

### `search_applications` (read only)

One page of the user's applications. All arguments are optional and combine with AND; a list matches any
of its values.

| Argument | Type | Meaning |
|---|---|---|
| `text` | string | words of the title, matched fuzzily |
| `companyId`, `contactId` | UUID | applications of this company, or with this linked contact |
| `statuses` | list of status | `DISCOVERED`, `SHORTLISTED`, `PREPARING`, `APPLIED`, `INTERVIEWING`, `OFFER`, `ACCEPTED`, `REJECTED`, ... (see `tools/list`) |
| `unread` | boolean | only unread (or read) applications |
| `languages` | list of BCP 47 tags | effective application language; `de` matches `de-CH` |
| `sourceKinds` | list of source kind | where the job was found |
| `createdFrom`, `createdTo`, `updatedFrom`, `updatedTo` | ISO-8601 instant | from inclusive, to exclusive |
| `sort`, `direction` | sort key, `ASCENDING`/`DESCENDING` | default: best title match with `text`, else most recently updated |
| `page`, `size` | integer | page from 0; size 1 to 50, default 20 |

Result: `{total, page, size, applications: [{id, companyId, status, unread, deadline, updatedAt,
posting: untrusted {title, location}}]}`.

### `get_application` (read only)

One application by `id` (UUID, required). Reading it does not mark it read.

Result: `{id, version, companyId, contactIds, status, declineCategory, declineReason, unread,
remoteSharePercent, employmentType, seniority, deadline, howApplied, portalNotes, payBand: {min, max,
currency, period, source}, wantScore, fitScore, createdAt, updatedAt, posting: untrusted {title, location,
sources: [{kind, url, discoveredAt, offlineSince}]}}`. Language and tone and the offer are not returned yet.
Errors: `not-found`, `unavailable`.

## Deleting (two-step confirmation)

The delete tools change data, so the server enforces the confirmation of ADR-0039 for them. What the server
guarantees: the model cannot skip, replay or redirect the confirmation, and nothing is deleted unless the client
reports a yes. What it cannot guarantee: that a person gave that yes. It asks through the client and trusts the
client's answer; a client that answers by itself deletes (see ADR-0039, "MCP and the built-in chat").

- A delete tool first runs the use case without a token. That mutates nothing and yields a single-use token (5
  minutes) bound to the caller, the MCP session, the operation, the target and the effect the server derived.
- The server then asks the client through MCP elicitation (form mode) to confirm. The text is the server's, in
  English: what is deleted, what is only unlinked, and the stored name on a line of its own, neutralised (one line;
  no control, bidi, invisible or quote characters; `* ~ [ ] < > # |` and backticks removed; at most two combining
  marks per character; at most 80 characters; `(empty)` if nothing is left) and labelled as stored text.
  Underscores, character entities and URLs are not changed, so a client that renders Markdown may format them. It passes the
  "never send to AI" filter first; if the filter fails nothing is asked. A checkbox carries the answer; only an
  `accept` with the box checked (a real boolean) runs the delete, by repeating the call with the token inside the
  server.
- The model never receives the token. Its result says only `deleted` or `declined`.
- The tool call waits for the answer for up to 4.5 minutes (`jofi.mcp.confirmation-timeout`, below the token's 5);
  the MCP SDK's 10 second default for server requests is raised accordingly. One confirmation per MCP session may
  wait at a time and at most `jofi.mcp.max-pending-confirmations` (default 4) in all, because a waiting call
  holds a server thread; further calls answer `confirmation-pending` at once and issue no token. The MCP SDK
  does not tell the server when a session closes, so a slot is freed when the wait ends, not earlier. A client
  that gives up on the call before the timeout still gets the delete if the user accepts later (the user did
  confirm). After a failed or timed-out delete call, re-read the entity (`get_application`, ...) before retrying
  or telling the user that nothing happened.
- A client that does not declare form elicitation (or has no session) cannot confirm: nothing is issued or deleted.
- If what the delete affects changed while the user was deciding, the token no longer matches and the tool
  answers `confirmation-invalid`; nothing is deleted. Call the tool again to start over.
- Each delete is logged in the changelog like every mutation, with the AI as actor (external clients get their
  own actor with #125); cascades follow the REST deletes. If the privacy filter fails after a delete happened, the
  result still says `deleted` (with ids only).

Additional error codes (nothing was deleted unless a result says `deleted`):

| Code | Meaning |
|---|---|
| `confirmation-unavailable` | the client cannot ask its user (no form elicitation, no session), the text was refused by the filter, or asking failed |
| `confirmation-timeout` | the user did not answer in time; call again to ask again |
| `confirmation-pending` | this session, or the server as a whole, already has the allowed number of confirmations waiting |
| `confirmation-invalid` | the confirmation no longer matches (the effect changed meanwhile) |
| `has-applications` | a company that still has applications |

### `delete_application`

Argument `id` (UUID, required). Deletes the application with its status history, interviews, sources and
description snapshots; tasks linked to it lose the link.
Result: `{status: "deleted" | "declined", kind: "application", id}`. Errors: `not-found`, `unavailable`.

### `delete_interview`

Arguments `applicationId` and `id` (UUIDs, required). Result as above with `kind: "interview"`.

### `delete_company`

Argument `id`. Deletes the company and its contacts; refused with `has-applications` while it has applications.
The confirmation names the contacts deleted with it and, as only unlinked, the applications and interviews its contacts
are removed from and the tasks that lose their link. Result with `kind: "company"`.

### `delete_contact`

Argument `id`. Deletes the contact with its channels and links. Result with `kind: "contact"`.

### `delete_task`

Argument `id`. Result with `kind: "task"`.
