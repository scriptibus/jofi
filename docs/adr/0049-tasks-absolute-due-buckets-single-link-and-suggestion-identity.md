<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0049: Tasks: absolute due buckets, one clearable link, suggestions identified by rule and key

- Status: accepted
- Date: 2026-09-30
- Source: spec §10.1 (countdowns), §10.2 (task list), §6.1 (follow-up rules); issue #80 (M1-C2e); builds on
  ADR-0041 and ADR-0048. Amended by #95 (M1-5c): the suggestion rules and accepting

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

The groups (`TaskCalendar`, #94) take "now" and "today" in the viewer's zone. An exact time is overdue once it has
passed, else grouped by its day on the viewer's clocks. A bucket is overdue from the day after it ends. One that has
begun is due now and groups by its span: a day is today, a week this week, a month this month (not by its first day,
which would put the current month into overdue or this week). A later week groups by its Monday like a day (next
week, this month or later); a later month is later. The rest of this month is what falls after next week and before
the next first day, so it is empty when next week reaches into the next month. Each group lists its tasks soonest
first by the end of their timing (the exact time, or the start of the day after the bucket, in the viewer's zone),
then oldest first; someday by age. Only open tasks are listed, read in one query by state (`task_state_idx`); done
tasks, pending suggestions (`GET /suggestions`) and dismissed ones are not.

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

The row changes without a new `version`, as the link is gone rather than edited. For now (#93) the task gets no
changelog entry of its own: the deleted entity's "Deleted …" entry is the trace. An entry per task needs the linked
task ids before the delete (the tasks context cannot find them once `SET NULL` ran), read through an SPI port the
tasks context implements, like `LinkedApplicationsPort`; that is #168. A suggestion whose application is deleted is obsolete and dismissed by its rule's next run (#95).

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

### Use cases (#93)

Create, edit, complete, reopen and delete follow ADR-0041: one transaction per mutation with its changelog entry,
the version checked first (even for a no-op), unchanged details or a task already in the target state store nothing
and record nothing, timestamps and the bucket's "today" from one `clock.instant()` cut to microseconds. The changelog
carries the timing (`2026-10-05T08:00:00Z Europe/Berlin`, `WEEK 2026-09-28`, `SOMEDAY`) and the link
(`contact:<id>`) as values, `state` for completing and reopening, and only the names of a changed title or notes;
a delete records the id alone. The delete's confirmation effect is `("task", <title>)` without counts, since nothing
goes with a task. The tasks domain defines no events yet: no other context reacts to tasks.

### Suggestion rules (amended by #95)

- **Three rules, one job.** `follow-up`, `interview-preparation` and `offer-answer` (each also its
  `Actor.System` name) run together in `SuggestTasksUseCase`, job `task-suggestions`: daily at 04:30 UTC with a
  random delay of up to 15 minutes (registered by `app`, run by the worker, ADR-0038), and after the applications
  context's events. The run asks the applications context what the rules are about through its named interface
  `api` (`FindSuggestionFactsPort`, plain values only; tasks → applications, never the reverse), suggests what is
  new and dismisses the waiting suggestions of these rules whose fact is gone (`reconcileSuggestions`, shared with
  the Ghosted run of ADR-0050). Accepted, done and other rules' suggestions are never touched.
- **Follow-up**: an `APPLIED` application without activity (as the Ghosted suggestion defines it) for the settings'
  `followUpAfterDays` (default 14, ADR-0050). Key `application:<id>:<last activity>` (one silence, one suggestion),
  due on the UTC day the period ended (Jofi keeps no user zone).
- **Interview preparation**: every interview still to come and not cancelled, due the day before it on the calendar
  of the zone it was planned in (ADR-0048). Key `interview:<id>:<that day>`: a reschedule to another day dismisses
  the old suggestion and makes a new one; one within the same day keeps it. Once the interview has begun, a waiting
  preparation is obsolete.
- **Offer answer**: an application at `OFFER` whose offer has `answerBy` today (UTC) or later, due the day before.
  Key `application:<id>:<answerBy>`: a new date is a new suggestion; a passed date, a removed date or a move on from
  `OFFER` makes the waiting one obsolete.
- **Events trigger, the daily run catches up.** A `@TransactionalEventListener` (after commit) hands every domain
  event to `RequestTaskSuggestionsUseCase`, which asks the applications context to name it
  (`DescribeApplicationEventPort`: `ApplicationStatusChanged`, `InterviewScheduled`, `InterviewRescheduled`) and, if
  it is one of those, queues a `task-suggestions` run. Queuing instead of running keeps the request fast, retries a
  failed run and writes nothing into the committed transaction. Facts without an event (an offer date edited, an
  interview cancelled or deleted) and a lost queue entry are caught by the next daily run. Runs are idempotent
  (`task_suggestion_unique`), so several queued runs do no harm.
- **Accept** (`POST /api/tasks/{id}/accept`, `AcceptTaskSuggestionUseCase`): `TaskTransition.ACCEPT` as the user,
  version checked first, the state change in the changelog in the same transaction. The task keeps its origin, so
  the rule never suggests it again.
- Titles are English like the Ghosted one ("Follow up: …", "Prepare for the interview: …", "Answer the offer: …");
  localized titles need a key per rule, left to the UI (#111).

## Consequences

- A task list is always relative to "now" in the viewer's zone, and a bucket task becomes overdue on its own.
- Deleting applications, companies or contacts never touches the user's tasks beyond clearing a link, so those
  deletes stay as they are.
- #95's rules need no lookup before suggesting: the unique constraint answers "already suggested or dismissed".
- If the spec later wants several links per task, the three columns become a link table; the API's `link` becomes a
  list (a breaking change to plan then).
