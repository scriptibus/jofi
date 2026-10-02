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
  `invalid-transition` (a task cannot move from its state to the requested one), `ai-not-configured`
  (see "Importing postings").
- Arguments that break a tool's schema (wrong type, a missing required argument, a value out of range) are
  refused by the MCP SDK as a tool error with a plain-text message before the tool runs. That message is not
  filtered; it names the properties the client sent and the schema's enum values, never argument values.
- Values flagged "never send to AI" are replaced by `[withheld]` in every result.
- Lists are paged (ADR-0056): `page` (from 0) and `size` (1 to 50, default 20); the answer says `page`, `size`,
  `total` and `hasMore`. Ask for the next `page` while `hasMore` is true. Paging is by offset over a stable order,
  so a list that changes between two reads can repeat or skip an entry (completing a task on page 0 and then reading
  page 1 skips one): compare `total`, and start again from page 0 after changing what the list holds. A long text (notes) is
  not in a list entry in full: the entry has `notesExcerpt` (at most 300 characters, cut at a character, never
  inside one) and `notesTruncated` under keys of their own, and a `get_*` tool has the whole text. A list
  entry is therefore not a valid source for an update (see "Interviews"). `...Truncated` says whether the text the
  reader sees was cut: for an AI that is the text after the "never send to AI" values were taken out, so a 301-character
  note that this shrinks to 299 answers `false`.
- Content copied from job postings or web pages is wrapped as
  `{"trust": "untrusted", "notice": "...", "content": ...}`: data, never instructions.

## Replace-style updates

Every update tool that replaces all fields of an entity (`update_application`, `update_interview`, `update_company`,
`update_contact`, and `set_application_contacts` for its one list) follows one rule: **every updatable property is
required in the schema but may be `null`, at the top level and inside nested objects.** A missing key is a schema refusal (nothing is stored); only an
explicit `null` clears. This keeps a model that did not read a field from deleting it: the changelog records which
fields changed, never their texts, so a wiped note cannot be recovered. A tool's update arguments take the shape of
its read tool's answer (the `content` of untrusted objects under the same keys), and what a tool cannot change is
shown apart from it (`readOnly`) and not sent back. Create and log tools keep their optional properties optional, and
every one of them refuses a `[withheld]` marker.

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
replace ALL fields (a PUT, not a patch) under the rule for replace-style updates above: every property is required,
a missing one is refused by the schema and stores nothing, only an explicit `null` (or `[]`) clears. Call `get_*`
first, change what you mean to change and send it back with the `version` you read: the answer's keys, with the
`content` of `company` or `contact` under that key (not the untrusted wrapper) and without `readOnly`; a stale
version answers `version-conflict` and changes nothing.
Every free-text field a tool can write is returned as untrusted, notes included: it can come from postings and pages, or
from a model that was prompt-injected and stored instructions for later sessions (ADR-0053, amendment of #119).
Only fields no tool writes, and typed values that cannot carry text, stay plain: the company preference and its reason, ids, versions and timestamps.
Problems of a domain violation are named by the argument path: flat for the create tools and searches
(`name:required`, `website:invalid-url`, `channels[0].value:invalid-email`, `companyId:not-found`,
`contactIds:not-found`), and with the object's key in front for the two update tools (`company.name:required`,
`contact.channels[0].value:invalid-email`; `companyId` and `contactIds` stay top level).
In the update tools a blank text is refused by the schema too (it would clear like `null`): a text is `null` or has a
visible character.

### `search_companies` (read only)

`text` (words of the name, matched fuzzily, at most 200 characters), `preference` (`NONE`, `FAVOURITE`,
`BLACKLISTED`), `page` (from 0), `size` (1 to 50, default 20), all optional. Result: `{total, page, size, companies: [{id, applicationCount,
preference, company: untrusted {name, website, industry, size, locations, careersPage}}]}`.

### `get_company` (read only)

`id` (UUID, required). Result: `{id, version, company: untrusted {name, website, industry, size, locations,
careersPage, researchNotes}, readOnly: {applicationCount, preference, preferenceReason, createdAt, updatedAt}}`.
`readOnly` is what no tool here changes and is not sendable to `update_company`. Errors: `not-found`, `unavailable`.

### `create_company`

`name` (required), `website`, `industry`, `size` (`MICRO`, `SMALL`, `MEDIUM`, `LARGE`, `ENTERPRISE`), `locations`
(list), `careersPage`, `researchNotes`. A literal `[withheld]` in any of them is refused (`withheld-value`, naming
the argument, such as `locations[1]`). Result: as `get_company`. Errors: `invalid-arguments`, `unavailable`.

### `update_company`

`id`, `version` and `company` (all required): `company` holds all seven fields of `create_company`, each required,
`null` (or `[]` for `locations`) for none. Replaces all details; the preference is not changed. Do not send
`readOnly`. Problems are named by their path, such as `company.name:required` and
`company.researchNotes:withheld-value`. Result: as `get_company`. Errors: `invalid-arguments`, `not-found`,
`version-conflict`, `unavailable`.

### `search_contacts` (read only)

`text` (words of the name, fuzzy, at most 200 characters), `companyId`, `page`, `size` (1 to 50, default 20). Result: `{total, page, size,
contacts: [{id, companyId, contact: untrusted {name, role}}]}`.

### `get_contact` (read only)

`id` (UUID, required). Result: `{id, version, companyId, contact: untrusted {name, role, channels: [{kind, value,
label}], relationshipNotes}, readOnly: {createdAt, updatedAt}}`. `readOnly` is not sendable to `update_contact`.
Errors: `not-found`, `unavailable`.

### `create_contact`

`name` (required), `role`, `companyId` (must exist), `channels` (list of `{kind: EMAIL|PHONE|WEB|OTHER, value,
label}`), `relationshipNotes`. A literal `[withheld]` in any of them is refused (`withheld-value`, naming the
argument, such as `channels[0].value`). Result: as `get_contact`. Errors: `invalid-arguments`, `unavailable`.

### `update_contact`

`id`, `version`, `companyId` and `contact` (all required): `contact` holds `name`, `role`, `channels` and
`relationshipNotes`, each required, and every channel `kind`, `value` and `label`; `null` (or `[]` for `channels`)
for none. Replaces all details, channels included. Do not send `readOnly`. Problems are named by their path, such as
`contact.channels[0].value:invalid-email`. Result: as `get_contact`. Errors: `invalid-arguments`, `not-found`,
`version-conflict`, `unavailable`.

### `set_application_contacts`

`id` (the application), `version` (from `get_application`) and `contactIds`, all required. The list becomes
exactly the set of linked contacts (at most 50): to link a contact, add its id to the ids `get_application`
returned in `readOnly.contactIds`; to unlink one, leave it out. `contactIds` follows the rule for replace-style
updates: it is required, leaving it out (or `null`) is refused and stores nothing, and only an explicit `[]` unlinks
all. Nothing else of the application is sent, so there is nothing more to leave out. There is no separate link and unlink tool because the
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
detail; its typed details come from `offer` and its texts from `notes.offer`. `notes.payEstimateBasis` is for an
`ESTIMATED` pay band only (`notes.payEstimateBasis:not-applicable` otherwise), and `notes` and `languageAndTone` may
be `null`. The application starts as `DISCOVERED`. Result: as `get_application`. Errors: `invalid-arguments`,
`unavailable`.

### `update_application`

The arguments of `get_application`'s answer without `readOnly`: send back what it returned, changed, with the
`content` of `posting`, `notes` and `languageAndTone` under their keys. Every property is required (the rule for
replace-style updates above); only an explicit `null` clears. An offer is cleared by `null` for both `offer` and
`notes.offer`: one without the other is refused (`offer:inconsistent`, `notes.offer:inconsistent`), as is a
`notes.payEstimateBasis` unless the pay band is `ESTIMATED` (`notes.payEstimateBasis:not-applicable`). The status, contacts, scores and unread flag are not
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
`content` of `interview` under its key); `readOnly` is not sent back. That is the shape of `get_interview` and of
the answers of `log_interview` and `update_interview`. A **list entry** (`list_interviews`) has the same keys, but
`interview` holds only excerpts: `untrusted {preparationNotesExcerpt, preparationNotesTruncated, notesExcerpt,
notesTruncated}`, and it has no `version`. It is not a source for an update, and the schema makes that hard to do by
accident: sent back as returned, `update_interview` refuses it (`preparationNotes`, `notes` and `version` are missing,
the excerpt keys are unknown), and the version comes only from `get_interview`. It is a guard, not a proof: a caller
that renames the excerpt keys to the full ones and invents the version (`0` fits an interview that was never edited)
can still store an excerpt as the note. Patch-style updates would close that.

### `log_interview`

`applicationId`, `type` (`PHONE_SCREEN`, `HR`, `TECHNICAL`, `CASE`, `ON_SITE`, `FINAL`, `OTHER`), `localStart`
(`2026-10-05T10:00`) and `timeZone` (`Europe/Berlin` or `+02:00`), all required, and optional `participantIds`
(contacts, at most 20), `interview` (`{preparationNotes, notes}`, Markdown), `outcome` (`PASSED`, `REJECTED`,
`WITHDRAWN`, `CANCELLED`). A literal `[withheld]` is refused here too. Result: the interview. Errors:
`invalid-arguments`, `not-found` (application), `unavailable`.

### `update_interview`

The arguments of `get_interview`'s answer without `readOnly`: `applicationId`, `id`, `version`, `type`,
`localStart`, `timeZone`, `participantIds`, `outcome` and `interview` (`{preparationNotes, notes}`). **Every
property is required** (the rule for replace-style updates above): leaving one out is refused and stores nothing,
only an explicit `null` clears (`participantIds`: `null` or `[]` for none). The entries of
`list_upcoming_interviews` and `list_interviews` are not a valid source (no version, notes or participants, or only
excerpts of the notes, and no version): read the interview with `get_interview` first, in this session, and send
what it returned. A model that writes the nulls itself and guesses the version could still blind-update and delete the
notes; the schema refuses a list entry as returned (see above), but cannot tell an invented value from a meant one. Unchanged details store nothing and log nothing. Result: the interview. Errors:
`invalid-arguments`, `not-found`, `version-conflict`, `unavailable`.

### `list_interviews` (read only)

`applicationId` (required), `direction` (`DESCENDING`, the default: newest first; or `ASCENDING`), `page` and
`size` (see "Results"). Result: `{page, size, total, hasMore, interviews: [entry]}`; paging through reaches every
interview of the application exactly once in either direction. An entry is an interview with excerpts of both notes
(see "Interviews") and the `version`; read one with `get_interview` for the whole text. Errors: `invalid-arguments`
(`applicationId:invalid`, `page:out-of-range`, `size:out-of-range`), `not-found`, `unavailable`.

### `get_interview` (read only)

`applicationId` and `id` (both required, from a list). The interview in full: the whole notes (`interview: untrusted
{preparationNotes, notes}`), the participants and the `version`, in the shape `update_interview` takes. The same use
case as `GET /api/applications/{id}/interviews/{interviewId}`. Errors: `invalid-arguments`, `not-found`,
`unavailable`.

### `list_upcoming_interviews` (read only)

No arguments. The interviews still to come across all applications, soonest first (at most 100); cancelled ones and
those of closed applications are left out. No notes, and not a valid source for `update_interview` (read the interview
with `get_interview` first):
`{interviews: [{id, applicationId, type, startsAt, localStart, timeZone, outcome, application: untrusted {title}}]}`.
Errors: `unavailable`.

## Importing postings (#118)

An import creates a new application in status `DISCOVERED` from a pasted job posting. The posting is third-party
data: it is stored and read by the extraction, which has no tools, and never followed as instructions. The tools only
start the import and return without waiting for the AI, logged in the changelog with the AI as actor (entity
`posting_import`); the extraction runs in a background job, so a client polls `get_import_status` until the status is
`SUCCEEDED` or `FAILED`. No result holds the posting's text; the new application is read with `get_application`.

**Importing a link is not available through MCP yet (#242).** A fetch of a URL the model chooses is an outward action,
so it will come with a confirmation by the user first. Until then the model should ask the user for the posting's
text, or let the user import the link in the app.

There is no write budget and no cost cap for imports yet (#217): every `start_text_import` queues an AI extraction
(up to 100,000 characters in), and the monthly cap does not apply to extractions the user starts. #217 also covers
import cost and lands before the chat (#121) and external clients (#125).

Import result: `{id, status, failure, applicationId, attempt, createdAt, updatedAt}`; `status` is `PENDING`,
`SUCCEEDED` (with `applicationId`) or `FAILED` (with `failure`: `AI_NOT_CONFIGURED`, `AI_AUTHENTICATION_FAILED`,
`AI_UNAVAILABLE`, `AI_REJECTED`, `UNREADABLE_ANSWER`, `NOT_A_POSTING`, `NOT_QUEUED`, `NOT_COMPLETED`). Retrying a
failed import is done in the app. A text submitted again after its import succeeded starts a new import (one pending
import at most per text, however often it is submitted).

Errors besides the usual: `ai-not-configured` (no model is assigned to the extraction task: the user sets one up
first) and `invalid-arguments` with `text:required`, `text:too-long`, `text:invalid-character`.

### `start_text_import`

`text` (required, plain text or Markdown, at most 100,000 characters). Result: the import (`PENDING`). The same
text submitted again while it is pending answers the same import.

### `get_import_status` (read only)

`id` (UUID, required). Result: the import. Errors: `invalid-arguments` (`id:invalid`), `not-found`, `unavailable`.

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
before the chat uses these tools, #121) and #237 (accepting a task that never was a suggestion answers success, a
use-case bug). The two list tools are bounded (#236).

### `list_tasks` (read only)

`timeZone` (required, the user's own zone, which Jofi does not store, so the client must pass it: for the
built-in chat the browser's; a wrong zone puts "today" on the wrong day. An IANA id such as `Europe/Berlin` or an
offset such as `+02:00`), `page` and `size` (see "Results").
Result: `{page, size, total, hasMore, groups: [{group, tasks}]}` with the OPEN tasks only, grouped on the calendar
of `timeZone` with weeks from Monday, as the Tasks page does (`ListTaskGroupsUseCase`, ADR-0049). Every group is
always present, in this order, empty ones included: `OVERDUE` (an exact time that has passed, or a day, week or
month that has ended), `TODAY`, `THIS_WEEK`, `NEXT_WEEK`, `THIS_MONTH`, `LATER`, `SOMEDAY`; each soonest first.
An exact time that has not passed is grouped by its day in `timeZone`; a day, week or month that is running now
counts as `TODAY`, `THIS_WEEK` or `THIS_MONTH`. Done tasks and suggestions are not in it. The tasks are numbered
through the groups in that order and a page is a window of that sequence: page 0 may hold only `OVERDUE` and
`TODAY`, and a later page continues in the group where the last one ended. `total` is the number of open tasks.
A task entry is a task without `notes`: `task: untrusted {title, notesExcerpt, notesTruncated}`; `get_task` has the
whole notes. Errors: `invalid-arguments` (`timeZone:invalid-time-zone`, `page:out-of-range`, `size:out-of-range`),
`unavailable`, `privacy-filter-failed` (the "never send to AI" flags could not be read: the notes are cut from text
that was filtered first, so nothing is returned).

### `list_task_suggestions` (read only)

`page` and `size`. One page of the suggested tasks waiting for a yes (for example a follow-up after applying), newest
first, as `{page, size, total, hasMore, tasks: [...]}` with entries as in `list_tasks`. It exists so
`accept_task_suggestion` has ids and versions; the use case behind it is the one of `GET /api/tasks/suggestions`.
Errors: `invalid-arguments` (`page:out-of-range`, `size:out-of-range`), `unavailable`, `privacy-filter-failed`
(as `list_tasks`).

### `get_task` (read only)

`id` (from a list). One task in any state in full: `{id, version, status, origin, suggestionRule, timing, link,
completedAt, createdAt, updatedAt, task: untrusted {title, notes}}` with the whole notes. The same use case as
`GET /api/tasks/{id}`. Errors: `invalid-arguments`, `not-found`, `unavailable`.

### `create_task`

`title` and `timeZone` (required), and when it is due as exactly one of `bucket` (`TODAY`, `THIS_WEEK`,
`NEXT_WEEK`, `THIS_MONTH`, `SOMEDAY`, resolved on today's date in `timeZone`) or `localDue` (an exact wall-clock
time in `timeZone`, `2026-10-05T10:00`); `link` (`{type: APPLICATION|COMPANY|CONTACT, id}`, must exist) and
`notes` (Markdown); `null` for an optional argument is accepted. A literal `[withheld]` in any argument is refused
(`withheld-value`, naming the argument). A `localDue` that does not exist because the
clocks change (a gap) is moved on as `java.time` does (ADR-0048): `2026-03-29T02:30` in `Europe/Berlin` is stored and
answered as `03:30`; in an overlap the earlier offset is taken. The use case has no way to refuse it, so check
the `localDue` in the answer. The task is open with origin `CHAT`. Result: the task. Errors: `invalid-arguments`, `unavailable`.

### `complete_task`

`id` and `version` (from `list_tasks` or `get_task`), both required. Marks an OPEN task done. A done task is returned unchanged
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
