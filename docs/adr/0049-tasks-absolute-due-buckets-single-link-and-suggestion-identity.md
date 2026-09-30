<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0049: Tasks: absolute due buckets, one clearable link, suggestions identified by rule and key

- Status: accepted
- Date: 2026-09-30
- Source: spec §10.1 (countdowns), §10.2 (task list), §6.1 (follow-up rules); issue #80 (M1-C2e); builds on
  ADR-0041 and ADR-0048

## Context

Tasks have flexible timing: a precise date and time, or a rough bucket ("today", "this week", "next week", "this
month", "someday"). They may be about an application, a company or a contact, and are created manually, through a
chat, or suggested by rules (#95) that the user accepts with one click. The dashboard also shows custom countdowns.
The questions:

- What does "this week" mean a week later? Is it stored as the word or as the week it meant?
- Whose calendar decides what "today" is, when the server's zone says nothing about the user?
- What happens to a task when the application, company or contact it is about is deleted?
- How do suggestion rules avoid suggesting the same task twice, or again after the user dismissed it?

## Decision

### Timing: an exact instant, or an absolute bucket of days

`TaskTiming` is sealed:

- `Exact(dueAt, zone)`: the model of ADR-0048 (instant plus the zone it was planned in, microsecond precision,
  2000 to 2100). Clients send `localDue` and `timeZone`.
- `Bucket(span, startsOn)`: `DAY` (that date), `WEEK` (from that Monday), `MONTH` (from that first day) or `SOMEDAY`
  (no date). Stored as `bucket_span` and `bucket_starts_on date`.

The relative words (`TimeBucket`: `TODAY`, `THIS_WEEK`, `NEXT_WEEK`, `THIS_MONTH`, `SOMEDAY`) are **input
vocabulary only**: the use case resolves them on today's date, taken from its clock in the zone the client sends
with the request (`timing.timeZone`, the zone the user is in), weeks starting on Monday. So "this week", picked on
Wednesday 30 September 2026, is stored as the week of Monday 28 September, and is overdue from Monday 5 October on.
Storing the word would make a task put into "today" due every day, and never overdue.

A bucket's days carry no zone: "due on Friday" is Friday wherever the user is. Reading a list therefore takes the
viewer's zone (`GET /api/tasks?timeZone=`, `GET /api/dashboard/countdowns?timeZone=`); the grouping (#94) is
overdue, today, this week, next week, this month, later, someday, where later holds what is due after this month
(spec §10.2 names no group for it). Jofi has no stored user time zone; if one comes, clients may stop sending it.

Custom countdowns count down to a `LocalDate` (`countdown.target_date`), counted on the viewer's calendar like
buckets. The derived dashboard countdowns (next interview, application and offer answer deadlines; the end of
employment in M2) are queries over the other contexts (#112) and never stored.

### One link, cleared when its target goes

A task links to at most one application, company or contact (`TaskLink`, implemented by the tasks context's own
reference types `ApplicationRef`, `CompanyRef`, `ContactRef`, ADR-0041), stored in three nullable columns with
`task_single_link` (`num_nonnulls(...) <= 1`). All three foreign keys are **`ON DELETE SET NULL`**: deleting the
target clears the link and keeps the task, its title still says what it was about. This is ADR-0041's rule for an
optional reference to `contact`, applied to all three so that:

- no delete elsewhere is blocked or grows (the application, company and contact deletes need not count tasks in
  their confirmation effects, and the tasks context needs no named interface of theirs);
- the user's own to-dos never disappear as a side effect of another delete.

The row changes without a new `version`, as the link is gone rather than edited. The tasks context reacts to
`ContactDeleted` and `ApplicationDeleted` for the rest (changelog, dismissing obsolete suggestions; #93, #95).

### States and origins

`TaskState`: `SUGGESTED` → `OPEN` (accept) or `DISMISSED`; `OPEN` → `DONE` (complete); `DONE` → `OPEN` (reopen).
Each is a `TaskTransition` with exactly one source state (`ACCEPT`, `DISMISS`, `COMPLETE`, `REOPEN`), applied with
`Task.apply`: a task already in the target state is unchanged, one in any other state is not allowed, so reopening
never accepts a suggestion and accepting never reopens a done task.
Nothing leaves `DISMISSED`. `completed_at` is set exactly while `DONE`. `TaskOrigin` is `Manual` (the app), `Chat`
(the built-in chat or an MCP client) or `Suggested(rule, key)`; only suggestions are ever `SUGGESTED` or
`DISMISSED`. Every change, a state change too, is a new `version` with `basedOnVersion` (ADR-0041).

### Suggestions are identified by rule and key

A suggestion stores its rule (kebab-case, e.g. `follow-up`, also the `Actor.System` name that suggested it) and a
key naming what it is for (e.g. `application:<id>`, `interview:<id>`). `task_suggestion_unique (suggestion_rule,
suggestion_key)` makes suggesting idempotent: a rule that runs again gets `SuggestionExists`, and a dismissed
suggestion stays in the table, so it is not suggested again. A suggestion that should come back (an interview
rescheduled) uses a new key. Direct tasks have neither, and `NULL`s never collide.

### API shape

`/api/tasks`: `GET ?timeZone=` (groups), `GET /suggestions`, `POST`, `GET|PUT|DELETE /{id}` (two steps,
`tasks.delete`), and `POST /{id}/complete|reopen|accept|dismiss` with `basedOnVersion` (separate operations, since
"to open" means reopen or accept depending on the stored state, which the controller must not decide).
`/api/countdowns`: `GET`, `POST`, `PUT|DELETE /{id}` (two steps, `countdowns.delete`); `GET
/api/dashboard/countdowns?timeZone=`. Changelog entity types `task` and `countdown`; entries name changed fields,
never titles or notes.

## Consequences

- A task list is always relative to "now" in the viewer's zone, and a bucket task becomes overdue on its own.
- Deleting applications, companies or contacts never touches the user's tasks beyond clearing a link, so those
  deletes stay as they are.
- #95's rules need no lookup before suggesting: the unique constraint answers "already suggested or dismissed".
- If the spec later wants several links per task, the three columns become a link table; the API's `link` becomes a
  list (a breaking change to plan then).
