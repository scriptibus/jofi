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
  `privacy-filter-failed` (the "never send to AI" flags could not be read, so nothing was returned),
  `version-conflict` (a write based on an older version of the entity: read it again and retry).
- Arguments that break a tool's schema (wrong type, a missing required argument, a value out of range) are
  refused by the MCP SDK as a tool error with a plain-text message before the tool runs. That message is not
  filtered; it names the properties the client sent and the schema's enum values, never argument values.
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

## Companies, contacts and contact links (#119)

The writes below create or change data and need no confirmation (spec §9: the chat may create and edit freely;
only deletes and outward actions are confirmed). Each is logged in the changelog with the AI as actor. Updates
replace ALL fields (a PUT, not a patch): a field left out is cleared. Call `get_*` first, change what you mean to
change and send everything back with the `version` you read (the fields of `company` or `contact` in the answer;
`null` means not set and is accepted); a stale version answers `version-conflict` and changes nothing.
Every field a tool can write is returned as untrusted, notes included: it can come from postings and pages, or
from a model that was prompt-injected and stored instructions for later sessions (ADR-0053, amendment of #119).
Only fields no tool writes stay plain: the company preference and its reason, ids, versions and timestamps.
Problems of a domain violation are named like `name:required`, `website:invalid-url`,
`channels[0].value:invalid-email`, `companyId:not-found`, `contactIds:not-found`.

### `search_companies` (read only)

`text` (words of the name, matched fuzzily, at most 200 characters), `preference` (`NONE`, `FAVOURITE`,
`BLACKLISTED`), `page` (from 0), `size` (1 to 50, default 20), all optional. Result: `{total, page, size, companies: [{id, applicationCount,
preference, company: untrusted {name, website, industry, size, locations, careersPage}}]}`.

### `get_company` (read only)

`id` (UUID, required). Result: `{id, version, applicationCount, preference, preferenceReason, createdAt,
updatedAt, company: untrusted {name, website, industry, size, locations, careersPage, researchNotes}}`. Errors: `not-found`, `unavailable`.

### `create_company`

`name` (required), `website`, `industry`, `size` (`MICRO`, `SMALL`, `MEDIUM`, `LARGE`, `ENTERPRISE`), `locations`
(list), `careersPage`, `researchNotes`. Result: as `get_company`. Errors: `invalid-arguments`, `unavailable`.

### `update_company`

`id`, `version` (both required) and the arguments of `create_company`. Replaces all details; the preference is not
changed. Result: as `get_company`. Errors: `invalid-arguments`, `not-found`, `version-conflict`, `unavailable`.

### `search_contacts` (read only)

`text` (words of the name, fuzzy, at most 200 characters), `companyId`, `page`, `size` (1 to 50, default 20). Result: `{total, page, size,
contacts: [{id, companyId, contact: untrusted {name, role}}]}`.

### `get_contact` (read only)

`id` (UUID, required). Result: `{id, version, companyId, createdAt, updatedAt, contact: untrusted {name, role,
channels: [{kind, value, label}], relationshipNotes}}`. Errors: `not-found`, `unavailable`.

### `create_contact`

`name` (required), `role`, `companyId` (must exist), `channels` (list of `{kind: EMAIL|PHONE|WEB|OTHER, value,
label}`), `relationshipNotes`. Result: as `get_contact`. Errors: `invalid-arguments`, `unavailable`.

### `update_contact`

`id`, `version` (both required) and the arguments of `create_contact`. Replaces all details, channels included.
Result: as `get_contact`. Errors: `invalid-arguments`, `not-found`, `version-conflict`, `unavailable`.

### `set_application_contacts`

`id` (the application), `version` (from `get_application`) and `contactIds`, all required. The list becomes
exactly the set of linked contacts (at most 50): to link a contact, add its id to the ids `get_application`
returned; to unlink one, leave it out; `[]` unlinks all. There is no separate link and unlink tool because the
use case replaces the set against one version. An unchanged set writes nothing. Result: as `get_application`.
Errors: `invalid-arguments` (`contactIds:not-found`, `contactIds:too-many`), `not-found`, `version-conflict`,
`unavailable`.

An update that sends back a value showing `[withheld]` (a flagged value the result hid) is refused with
`invalid-arguments` (`withheld-value`) and changes nothing, because the replace-all update would store the marker
over the real value. A contact or company with a flagged value therefore cannot be updated through these tools
until they get patch-style updates.
