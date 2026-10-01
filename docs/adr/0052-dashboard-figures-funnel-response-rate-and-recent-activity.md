<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0052: Dashboard figures: funnel and response rate over the status history, upcoming tasks, recent activity

- Status: accepted
- Date: 2026-10-01
- Source: spec §10.1 (dashboard), §10.2, §13; issue #113 (M1-11a2); builds on ADR-0043, ADR-0044, ADR-0048, ADR-0049

## Context

The dashboard (spec §10.1) shows pipeline counts per status, a funnel "applied → interview → offer" with a response
rate, the unread count, upcoming tasks, recent activity and the AI cost this month against the budget. The spec does
not say:

- what "reached an interview" means when the pipeline allows skips and reopening (ADR-0044): an application can go
  from Discovered straight to Offer, or from Rejected back to Discovered;
- what counts as a response, and over which applications the rate is taken;
- how far "upcoming" reaches, and in whose calendar;
- which changelog entries are "activity", and what an entry may show without leaking free text.

Each figure belongs to the context that owns its data. Applications must never depend on tasks (ADR-0049), and no
context may read another's internals.

## Decision

### Where each figure lives (one endpoint per area)

| Figure | Context | Endpoint |
|---|---|---|
| Applications per status, unread count, funnel, response rate | applications (`GetPipelineOverviewUseCase`) | `GET /api/dashboard/pipeline` |
| Recent activity | applications (`ListRecentActivityUseCase`) | `GET /api/dashboard/activity?limit=` |
| Overdue and upcoming tasks | tasks (`GetTaskDashboardUseCase`) | `GET /api/dashboard/tasks?timeZone=` |
| Countdowns | tasks (#112) | `GET /api/dashboard/countdowns?timeZone=` |
| AI cost this month against the budget | setup (`GetCostSummaryUseCase`, #24) | `GET /api/setup/costs` |

The AI cost needs nothing new: the current month's cost summary already carries the known cost, the calls with an
unknown cost and the budget usage, summed exactly as the gateway's budget check sums them (UTC calendar months,
ADR-0043). A second endpoint would only duplicate it. No figure crosses a context boundary, so no named interface
is added, and the client asks each area separately (one slow figure does not hold up the others).

### The funnel counts the status history, not the current status

An application **has reached** a stage when its status history (`application_status_change`, ADR-0044) holds any
status of the stage, whatever its status is now:

| Stage | Statuses in the history |
|---|---|
| Applied | `APPLIED`, `INTERVIEWING`, `OFFER`, `ACCEPTED`, `REJECTED`, `WITHDRAWN`, `GHOSTED` (`ApplicationStatus.impliesApplied`) |
| Interview | `INTERVIEWING`, `OFFER`, `ACCEPTED` |
| Offer | `OFFER`, `ACCEPTED` |
| Responded | `INTERVIEWING`, `OFFER`, `ACCEPTED`, `REJECTED` |

- **A later stage implies the earlier ones.** A skip from Discovered to Offer counts as applied, interviewed and
  offered; `ACCEPTED` is only reachable from `OFFER`, and `REJECTED`, `WITHDRAWN` and `GHOSTED` only after applying
  (ADR-0044). Each stage's statuses contain the next stage's, so the funnel never widens.
- **Reopening keeps the history.** A rejected application moved back to Discovered still counts as applied and
  responded: the funnel describes what happened, the per-status counts describe where things stand now.
- **`DECLINED` is not "applied"**: the user may decline before applying. A decline on an offer counts through the
  `OFFER` entry before it.
- **Response** = the company answered: an interview, an offer (or its acceptance) or a rejection. Withdrawn without
  an answer, Ghosted and still-waiting applications have none. A late answer after Ghosted (Ghosted → Rejected,
  ADR-0044) counts as one.
- **Rates** are shares from 0 to 1 with their base: interview rate = interviewed / applied, offer rate = offered /
  interviewed, response rate = responded / applied. A rate whose base is zero is absent (not 0), so "no applications
  yet" is not shown as "0 % answered".
- **All time, deleted applications excluded.** The history is deleted with its application (`ON DELETE CASCADE`),
  so the funnel covers the applications that exist. A time window (e.g. applied in the last 90 days) is left to the
  analytics of M6.
- One aggregate query: `count(distinct application_id) filter (where to_status in (...))` per stage, so the stages
  come from one consistent read. The stage sets are the domain's `FunnelStage`, which the query uses directly.

### Overdue and upcoming tasks on the viewer's calendar

`TaskCalendar.dashboard` (ADR-0049) reads the open tasks once and splits them at "now" in the viewer's zone (sent as
`timeZone`, like the task list, since Jofi keeps no user zone):

- **Overdue** is exactly the task list's overdue group: an exact time that has passed, a bucket whose last day is
  before today.
- **Upcoming** is the rest that is due **today or on one of the six days after it** (seven calendar days): an exact
  time by its day on the viewer's clocks, a bucket by its last day. So "this week" counts once its Sunday is within
  the seven days (from Monday on, always), "this month" in its last week, and someday never. Calendar days, not
  168 hours, so a task due at 23:00 on the seventh day is in and the list does not change by the hour.
- Each list is soonest first, as in the task list. Done tasks and suggestions are not shown.

### Recent activity: fixed texts, field names and ids, labelled by the application

- The newest `limit` (1 to 100, default 20) changelog entries whose entity type is part of the job search:
  `application`, `application_source`, `description_snapshot`, `interview`, `company`, `contact`, `task`,
  `countdown`. Settings, saved views, the AI setup and system entries (sessions, backups, passwords) are
  housekeeping and would crowd out the job search (the hourly session clean-up writes an entry whenever sessions
  expired). The other
  contexts' types are their changelog names, which never change; a domain test pins them to the constants.
- An entry shows its number, time, actor (kind and name, as everywhere), entity type and id, its **description**
  (a fixed English text the code writes, e.g. "Created application") and the **names** of the changed fields. It
  never shows field values or the reason: values can be free text (titles, notes, names), and the dashboard does not
  need them. The application timeline (#87) remains the place for a change's details.
- **Label**: an entry about an application, its interview, source or description snapshot names that application
  with its id and job title, the label the application list shows, read in one query while the application exists.
  Entries of other contexts (companies, contacts, tasks, countdowns) carry ids only: labelling them would read
  another context's tables. If the UI needs their names, those contexts expose a lookup through a named interface.
- Served by `changelog_entry_recent_idx` (`occurred_at`, `id`) read backwards; ties go by number.

## Consequences

- The UI (#114) asks four endpoints for the dashboard plus the countdowns; each can fail on its own (503) without
  hiding the others.
- The funnel answers "what happened", so it never shrinks when an application moves back; the per-status counts
  answer "where things stand". The UI labels them accordingly.
- A new status needs a decision on its funnel stages (`FunnelStage`) as well as on its moves (ADR-0044).
- A new changelog entity type is not on the dashboard until it is added to `ActivityQuery.ENTITY_TYPES`.
- These are reads only: no table, no migration, nothing new for export/import, no changelog entry.
