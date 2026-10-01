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
