<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0044: Application status pipeline: transition matrix, decline reason and history

- Status: accepted
- Date: 2026-09-30
- Source: spec §6.1, §6.2; issue #77 (M1-C2b) and the hand-over from PR #141 on it; tech-stack proposal
  §4.2 (State pattern); refines ADR-0041

## Context

An application moves through `Discovered → Shortlisted → Preparing → Applied → Interviewing → Offer` and
ends as `Accepted`, `Rejected` (by the company), `Withdrawn` (by the user after applying), `Declined` (the
user decided against it, before applying or on the offer) or `Ghosted` (no answer; suggested after 14
weeks, #85). Declined and rejected jobs stay visible and filterable with their reasons, and the history is
complete and timestamped (spec §6.2). The questions:

- Which moves are allowed, and can an ended application come back?
- Which moves need a reason, and how does the per-move reason relate to the application's own
  `DeclineReason` (category + text, spec §6.1), which #141 put into the details?
- How is the status stored without letting a detail edit overwrite a status change, or the reverse?

## Decision

### The status is a State pattern over an enum

`ApplicationStatus` (domain) names the eleven statuses. Each decides which statuses it may move to, in one
exhaustive `when` (`canMoveTo`), so adding a status does not compile until its moves are decided.
`Application.changeStatus(request, actor, at)` applies a move and returns a sealed `StatusTransition`:
`Changed` (the new version, the history entry `StatusChange` and the event `ApplicationStatusChanged`),
`Unchanged` or `NotAllowed(from, to)`. The use case (#84) maps `NotAllowed` to
`ApplicationResult.InvalidTransition`, answered `409 urn:jofi:problem:applications:invalid-transition`.

### Transition matrix

`x` = allowed, rows are the current status. `ApplicationStatusTest` checks all 121 pairs against this table.

| from \ to | DIS | SHO | PRE | APP | INT | OFF | ACC | REJ | WIT | DEC | GHO |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Discovered | | x | x | x | x | x | | | | x | |
| Shortlisted | x | | x | x | x | x | | | | x | |
| Preparing | x | x | | x | x | x | | | | x | |
| Applied | x | x | x | | x | x | | x | x | | x |
| Interviewing | x | x | x | x | | x | | x | x | | x |
| Offer | x | x | x | x | x | | x | x | | x | |
| Accepted | | | | | | x | | | | | |
| Rejected | | | | x | x | x | | x | | | |
| Withdrawn | | | | x | x | | | | | | |
| Declined | x | x | x | | | x | | | | x | |
| Ghosted | | | | x | x | x | | x | x | | |

- **Within the pipeline every move is allowed**: forward skips (Discovered straight to Applied, for a job
  found and applied to on the same day) and backward corrections (a mis-click on Interviewing). The
  history records each move, so a correction stays visible.
- **Ending**: `Declined` before applying or on the offer; `Rejected` and `Withdrawn` only after applying
  (`Withdrawn` not from `Offer`: saying no to an offer is `Declined`); `Accepted` only from `Offer`;
  `Ghosted` from `Applied` and `Interviewing`.
- **Reopening a terminal status** goes back to where it can be entered from: `Declined` to the pre-applying
  statuses and `Offer`, `Rejected` to `Applied`/`Interviewing`/`Offer` (the company comes back), `Withdrawn`
  to `Applied`/`Interviewing`, `Accepted` to `Offer` (the offer fell through). `Ghosted` is left on a late
  reply: back to `Applied`, `Interviewing` or `Offer`, or on to `Rejected` or `Withdrawn`. Terminal
  statuses do not move to each other otherwise; that goes through a reopening, which the history shows.
- **Moving to the current status** is a no-op (`Unchanged`, no new version, no entry, no changelog entry),
  so a retried request does no harm, with one exception: `Declined → Declined` and `Rejected → Rejected`
  with a *different* decline reason correct the reason and are recorded like any move.

### Reasons

- Every move takes an optional free-text `reason` (Markdown, at most 5,000 characters), kept on the
  history entry.
- A move to `Declined` or `Rejected` **requires a decline category** (`NO_REASON_GIVEN` exists for
  rejections without one); any other move must not carry one (`NOT_APPLICABLE`). Category and reason
  become the application's `DeclineReason` **in the same change**, and the history entry keeps its own
  copy of both.
- **The decline reason belongs to the status, not to the details**: `Application.declineReason` is set
  exactly while the status is `Declined` or `Rejected` (an invariant), and it leaves `ApplicationDetails`,
  `ApplicationInput` and the details request (#141 had put it there; no endpoint was implemented yet, and
  removing an optional request property is not a breaking change for oasdiff). It is still in
  `ApplicationResponse`. Otherwise a detail edit could set a reason on an applied job or clear it on a
  declined one, and two writers would own the same columns.
- **Reopening clears it**: any move to a status other than `Declined`/`Rejected` sets it to none; the
  history keeps what it was, so the "why" of the earlier ending is never lost.

### Storage

- `application.status` (`NOT NULL DEFAULT 'DISCOVERED'`, a constant default, so adding it rewrites no
  table) and `application_status_change` (identity `id` for the order, `from_status` NULL only for the
  first entry, `to_status`, `reason`, `decline_category`, actor as in `changelog_entry`, `changed_at`),
  `ON DELETE CASCADE` from the application. Every constraint is named and mirrors the domain, never
  stricter; the matrix itself is the domain's job.
- **Status changes are their own versioned write** (`ApplicationRepositoryPort.changeStatus`): `status`,
  `decline_category`, `decline_reason`, `version` and `updated_at` plus the appended history row, both or
  neither, under the version check. `updateDetails` never writes these columns, and `changeStatus` never
  writes the details, `unread`, the scores or the links (ADR-0041's "no write overwrites what it does not
  own").
- Creating an application writes its first history entry (`StatusChange.initial`, `from` NULL), so every
  application has a complete history from its creation.
- **Existing rows** (and older backups migrated in the scratch database) start as `Discovered`, or as
  `Declined` if they hold a decline reason (the reading that claims least), and each gets one entry by
  `System("status-history-backfill")` at its `created_at`. The status-dependent check
  `application_decline_reason_matches_status` is added `NOT VALID` and validated after that (ADR-0041).
- Changelog: a status change records the field `status` (before, after) with the actor; the reason text
  stays out of the changelog (free text, #52). `ApplicationStatusChanged` carries ids and statuses only.
- Export/import: `application_status_change` is in `BackupTables.EXPORTED`.

## Consequences

- #84 implements the use cases (`ChangeApplicationStatusPort`, `GetApplicationStatusHistoryPort`), the
  repository methods and the endpoints (`PUT /api/applications/{id}/status`,
  `GET /api/applications/{id}/status-history`); #85 suggests `Ghosted` through the same use case with a
  `System` actor; #83 filters by status and decline category.
- The reason of a declined or rejected application is edited by moving to the same status with the new
  reason, which leaves a history entry, rather than through the details.
- A new status or move is a change to `ApplicationStatus`, this ADR's table and `ApplicationStatusTest`,
  plus the enum checks in the migration (a new migration, never an edited one).
