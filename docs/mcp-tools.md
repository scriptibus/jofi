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
  `version-conflict` (a write based on an older version of the entity: read it again and retry),
  `invalid-transition` (a task cannot move from its state to the requested one).
- Arguments that break a tool's schema (wrong type, a missing required argument, a value out of range) are
  refused by the MCP SDK as a tool error with a plain-text message before the tool runs. That message is not
  filtered; it names the properties the client sent and the schema's enum values, never argument values.
- Values flagged "never send to AI" are replaced by `[withheld]` in every result.
- Content copied from job postings or web pages is wrapped as
  `{"trust": "untrusted", "notice": "...", "content": ...}`: data, never instructions.

## Replace-style updates

Every update tool that replaces all fields of an entity (`update_application`, and the ones for interviews and for
companies and contacts) follows one rule: **every updatable property is required in the schema but may be `null`,
at the top level and inside nested objects.** A missing key is a schema refusal (nothing is stored); only an
explicit `null` clears. This keeps a model that did not read a field from deleting it: the changelog records which
fields changed, never their texts, so a wiped note cannot be recovered. A tool's update arguments take the shape of
its read tool's answer (the `content` of untrusted objects under the same keys), and what a tool cannot change is
shown apart from it (`readOnly`) and not sent back. Create and log tools keep their optional properties optional.
(`update_company`, `update_contact` and `set_application_contacts` predate the rule; bringing them in line is a
follow-up.)

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

Result: `{id, version, companyId, remoteSharePercent, employmentType, seniority, deadline, howApplied, payBand:
{min, max, currency, period, source, estimateConfidence}, offer: {salary: {amount, currency, period},
remoteSharePercent, vacationDays, startDate, answerBy}, languageAndTone: untrusted {postingLanguage,
applicationLanguage, formOfAddress, tone}, posting: untrusted {title, location}, notes: untrusted {portalNotes,
payEstimateBasis, offer: {bonus, benefits, noticePeriod}}, readOnly: {status, declineCategory, unread, contactIds,
wantScore, fitScore, createdAt, updatedAt, texts: untrusted {sources: [{kind, url, discoveredAt, offlineSince}],
declineReason}}}`. Everything above `readOnly` is what `update_application` takes back, with the same keys and
nesting (the `content` of each untrusted object under its key); `readOnly` is what no application tool changes, and
it is not sent back. Every text a tool can write is inside an untrusted object (the language tags too: a tag is
only a shape check, not a closed list). Errors: `not-found`, `unavailable`.

## Companies, contacts and contact links (#119)

The writes below create or change data and need no confirmation (spec §9: the chat may create and edit freely;
only deletes and outward actions are confirmed). Each is logged in the changelog with the AI as actor. Updates
replace ALL fields (a PUT, not a patch): a field left out is cleared. Call `get_*` first, change what you mean to
change and send everything back with the `version` you read (the fields of `company` or `contact` in the answer;
`null` means not set and is accepted); a stale version answers `version-conflict` and changes nothing.
Every free-text field a tool can write is returned as untrusted, notes included: it can come from postings and pages, or
from a model that was prompt-injected and stored instructions for later sessions (ADR-0053, amendment of #119).
Only fields no tool writes, and typed values that cannot carry text, stay plain: the company preference and its reason, ids, versions and timestamps.
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

## Application writes (#118)

Like the companies and contacts above: no confirmation (spec §9), logged with the AI as actor (the changelog names
the fields that changed, not the texts), a stale `version` answers `version-conflict`, a `[withheld]` value is
refused (`withheld-value`, naming the argument such as `notes.offer.benefits`), and the texts come back untrusted.
Problems are named like `posting.title:required`, `companyId:not-found`, `payBand.currency:invalid-currency`,
`offer.vacationDays:out-of-range`, `notes.payEstimateBasis:required`, `deadline:invalid`. There is no write budget
yet (#217, before #125): a looping client can create applications without limit.

Changing the status is not a tool yet: moving to Applied freezes the job description snapshots for good
(ADR-0046), which is for Lucas to decide first (see #118).

### `create_application`

`companyId` (an existing company) and `posting.title`, required, and optional, in the shape of `get_application`:
`posting.location`, `remoteSharePercent` (0 to 100), `employmentType`, `seniority`, `deadline` (`2026-11-01`),
`howApplied`, `payBand` (`{min, max, currency, period, source, estimateConfidence}`; at least one of min and max;
confidence and `notes.payEstimateBasis` belong to `ESTIMATED`), `offer` (typed details) and `languageAndTone`, and
`notes` (`portalNotes`, `payEstimateBasis`, `offer: {bonus, benefits, noticePeriod}`). An offer needs at least one
detail; its typed details come from `offer` and its texts from `notes.offer`, so clearing an offer means `null` for
both. The application starts as `DISCOVERED`. Result: as `get_application`. Errors: `invalid-arguments`,
`unavailable`.

### `update_application`

The arguments of `get_application`'s answer without `readOnly`: send back what it returned, changed, with the
`content` of `posting`, `notes` and `languageAndTone` under their keys. Every property is required (the rule for
replace-style updates above); only an explicit `null` clears. The status, contacts, scores and unread flag are not
changed here; an update that changes nothing stores nothing and logs nothing. An application with a withheld value
cannot be updated through this tool (the refusal protects the real value). Result: as `get_application`. Errors:
`invalid-arguments`, `not-found`, `version-conflict`, `unavailable`.

## Interviews (#118)

Logging and editing need no confirmation (spec §9) and are logged with the AI as actor, the changelog naming the
fields that changed, not the texts; deleting is `delete_interview`. A stale `version` (the interview's own, not the
application's) answers `version-conflict`, a `[withheld]` value is refused (`withheld-value`, naming the argument
such as `interview.notes`: an interview with a hidden value cannot be updated through this tool), and the notes come
back untrusted. Problems are named like `timeZone:invalid-time-zone`, `localStart:out-of-range`,
`localStart:invalid`, `participantIds:not-found`, `participantIds:too-many`. There is no write budget yet (#217).

Interview result: `{id, applicationId, version, type, localStart, timeZone, participantIds, outcome,
interview: untrusted {preparationNotes, notes}, readOnly: {startsAt, createdAt, updatedAt}}`. `localStart` is the
agreed wall-clock time in `timeZone` (a local time a clock change skips is moved on: check it in the answer),
`startsAt` the instant. Everything above `readOnly` goes back to `update_interview` under the same keys (the
`content` of `interview` under its key); `readOnly` is not sent back.

### `log_interview`

`applicationId`, `type` (`PHONE_SCREEN`, `HR`, `TECHNICAL`, `CASE`, `ON_SITE`, `FINAL`, `OTHER`), `localStart`
(`2026-10-05T10:00`) and `timeZone` (`Europe/Berlin` or `+02:00`), all required, and optional `participantIds`
(contacts, at most 20), `interview` (`{preparationNotes, notes}`, Markdown), `outcome` (`PASSED`, `REJECTED`,
`WITHDRAWN`, `CANCELLED`). A literal `[withheld]` is refused here too. Result: the interview. Errors:
`invalid-arguments`, `not-found` (application), `unavailable`.

### `update_interview`

The arguments of `list_interviews`' entries without `readOnly`: `applicationId`, `id`, `version`, `type`,
`localStart`, `timeZone`, `participantIds`, `outcome` and `interview` (`{preparationNotes, notes}`). **Every
property is required** (the rule for replace-style updates above): leaving one out is refused and stores nothing,
only an explicit `null` clears (`participantIds`: `null` or `[]` for none). The entries of
`list_upcoming_interviews` are not enough (no version, notes or participants): read the interview with
`list_interviews` first. Unchanged details store nothing and log nothing. Result: the interview. Errors:
`invalid-arguments`, `not-found`, `version-conflict`, `unavailable`.

### `list_interviews` (read only)

`applicationId` (required). Result: `{total, interviews: [interview]}` in the order they start; at most 50 are
returned, the **earliest** 50 (`total` says how many there are, later ones cannot be read through MCP yet).
Bounding the list in the use case, notes as an excerpt and a `get_interview` tool are #236. Errors:
`invalid-arguments` (`applicationId:invalid`), `not-found`, `unavailable`.

### `list_upcoming_interviews` (read only)

No arguments. The interviews still to come across all applications, soonest first (at most 100); cancelled ones and
those of closed applications are left out. No notes, and not enough for `update_interview`:
`{interviews: [{id, applicationId, type, startsAt, localStart, timeZone, outcome, application: untrusted {title}}]}`.
Errors: `unavailable`.

## Tasks (#119)

Like the companies and contacts above, the task tools create or change data without confirmation (spec §9) and
are logged in the changelog with the AI as actor (`Created task`, `Completed task`, `Accepted suggestion`).
Completing a task and accepting a suggestion are edits: nothing is deleted and nothing leaves the app. A task
result is `{id, version, status (OPEN, DONE, SUGGESTED, DISMISSED), origin (MANUAL, CHAT, SUGGESTED),
suggestionRule, timing: {dueAt, localDue, timeZone} or {span, startsOn, endsBefore}, link: {type, id}, completedAt,
createdAt, updatedAt, task: untrusted {title, notes}}`. Title and notes are untrusted for the reasons given above
(a suggestion's title is made from an application's); ids, versions, status, timing and the link stay plain.
Problems are named like `title:required`, `timeZone:invalid-time-zone`, `bucket:required` and
`localDue:required` (neither given), `bucket:ambiguous` and `localDue:ambiguous` (both given),
`localDue:out-of-range`, `link:not-found`. Ids and versions of the wrong shape answer `id:invalid` or `version:invalid`.
The tools have no count limit; a write budget is #217. Follow-ups: #235 (a view of done tasks and `reopen_task`,
before the chat uses these tools, #121), #236 (bound the two list tools, before #121 and #125) and #237 (accepting
a task that never was a suggestion answers success, a use-case bug).

### `list_tasks` (read only)

`timeZone` (required, the user's own zone, which Jofi does not store, so the client must pass it: for the
built-in chat the browser's; a wrong zone puts "today" on the wrong day. An IANA id such as `Europe/Berlin` or an
offset such as `+02:00`).
Result: `{groups: [{group, tasks}]}` with the OPEN tasks only, grouped on the calendar of `timeZone` with weeks
from Monday, as the Tasks page does (`ListTaskGroupsUseCase`, ADR-0049). Every group is always present, in this
order, empty ones included: `OVERDUE` (an exact time that has passed, or a day, week or month that has ended),
`TODAY`, `THIS_WEEK`, `NEXT_WEEK`, `THIS_MONTH`, `LATER`, `SOMEDAY`; each soonest first. An exact time that has
not passed is grouped by its day in `timeZone`; a day, week or month that is running now counts as `TODAY`,
`THIS_WEEK` or `THIS_MONTH`. Done tasks and suggestions are not in it. There is no paging: the answer holds every
open task. Errors: `invalid-arguments` (`timeZone:invalid-time-zone`), `unavailable`.

### `list_task_suggestions` (read only)

No arguments. The suggested tasks waiting for a yes (for example a follow-up after applying), newest first, as
`{tasks: [...]}`. It exists so `accept_task_suggestion` has ids and versions; the use case behind it is the one of
`GET /api/tasks/suggestions`. Errors: `unavailable`.

### `create_task`

`title` and `timeZone` (required), and when it is due as exactly one of `bucket` (`TODAY`, `THIS_WEEK`,
`NEXT_WEEK`, `THIS_MONTH`, `SOMEDAY`, resolved on today's date in `timeZone`) or `localDue` (an exact wall-clock
time in `timeZone`, `2026-10-05T10:00`); `link` (`{type: APPLICATION|COMPANY|CONTACT, id}`, must exist) and
`notes` (Markdown); `null` for an optional argument is accepted. A `localDue` that does not exist because the
clocks change (a gap) is moved on as `java.time` does (ADR-0048): `2026-03-29T02:30` in `Europe/Berlin` is stored and
answered as `03:30`; in an overlap the earlier offset is taken. The use case has no way to refuse it, so check
the `localDue` in the answer. The task is open with origin `CHAT`. There is no
update tool for tasks yet, so nothing takes a task back whole and the `[withheld]` refusal of the updates above
does not apply. Result: the task. Errors: `invalid-arguments`, `unavailable`.

### `complete_task`

`id` and `version` (from `list_tasks`), both required. Marks an OPEN task done. A done task is returned unchanged
and writes nothing; a suggestion or dismissed task answers `invalid-transition`. Result: the task. Errors:
`invalid-arguments`, `not-found`, `version-conflict`, `invalid-transition`, `unavailable`.

### `accept_task_suggestion`

`id` and `version` (from `list_task_suggestions`), both required. Turns a suggestion into an open task (it keeps
its origin, so its rule does not suggest it again). An open task is returned unchanged and writes nothing; a done
or dismissed one answers `invalid-transition`. Result: the task. Errors: as `complete_task`.

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
