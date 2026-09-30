<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0044: Application status pipeline: transition matrix, decline reason and history

- Status: accepted
- Date: 2026-09-30
- Source: spec §6.1, §6.2; issue #77 (M1-C2b) and the hand-over from PR #141 on it; tech-stack proposal
  §4.2 (State pattern); refines ADR-0041. Amended by issue #84 (M1-1c, decisions from the #148 review): the
  looser reopening matrix, the changelog of reason corrections, self-move events and the synchronous freeze

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
| Offer | x | x | x | x | x | | x | x | | x | x |
| Accepted | x | x | x | x | x | x | | x | | x | |
| Rejected | x | x | x | x | x | x | | x | | | |
| Withdrawn | x | x | x | x | x | x | | | | | |
| Declined | x | x | x | x | x | x | | | | x | |
| Ghosted | x | x | x | x | x | x | | x | x | | |

- **Within the pipeline every move is allowed**: forward skips (Discovered straight to Applied, for a job
  found and applied to on the same day) and backward corrections (a mis-click on Interviewing). The
  history records each move, so a correction stays visible.
- **Ending**: `Declined` before applying or on the offer; `Rejected` and `Withdrawn` only after applying
  (`Withdrawn` not from `Offer`: saying no to an offer is `Declined`); `Accepted` only from `Offer`;
  `Ghosted` from `Applied`, `Interviewing` and `Offer` (an offer that is never followed up).
- **Reopening (amended by #84)**: every terminal status may move back to **every** pipeline status. The
  first matrix only allowed going back to where a status can be entered from, which forced detours for
  real cases (a declined job the user applies to after all, an accepted offer that ends before the start);
  the history records every move, so nothing is lost by allowing them directly.
- **Between terminal statuses** only these: `Accepted` to `Rejected` (the offer was rescinded) or
  `Declined` (the user reneged), and `Ghosted` on to `Rejected` or `Withdrawn` on a late answer. Anything
  else goes through a reopening, which the history shows.
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
- Changelog: a status change records the field `status` (before, after) with the actor, plus the field
  `declineReason` (the category before, after) when the category changes, e.g. on declining or reopening. A
  self-move that corrects the reason (`Declined → Declined`, `Rejected → Rejected`) is described as
  "Corrected decline reason" and records only `declineReason`, never a meaningless `status` `DECLINED →
  DECLINED`; if only the text changed, it names no field (the history entry holds the new text). The reason
  text stays out of the changelog (free text, #52).
- `ApplicationStatusChanged` is a `shared.domain.DomainEvent` carrying ids and statuses only, published through
  `DomainEventPort` inside the change's transaction. **Its `from` may equal its `to`** for such a reason
  correction; consumers must tolerate a move that leaves the status as it was.
- **Description freeze (ADR-0046)**: when a move `freezesDescriptions` (into `Applied` or later from a status
  that has not applied, including `Declined`), `ChangeApplicationStatusUseCase` calls
  `DescriptionSnapshotRepositoryPort.freeze(application, change time)` synchronously in the same transaction
  and writes one changelog entry per frozen snapshot (entity `description_snapshot`, field `frozenAt`, the
  move's actor). Only the first freeze counts: a reopening that applies again (e.g. `Offer → Declined →
  Applied`) freezes nothing new.
- Export/import: `application_status_change` is in `BackupTables.EXPORTED`.

## Consequences

- #84 implemented the use cases (`ChangeApplicationStatusUseCase`, `GetApplicationStatusHistoryUseCase`) and
  the endpoints (`PUT /api/applications/{id}/status`,
  `GET /api/applications/{id}/status-history`); #83 filters by status and decline category.
- **Ghosted is only ever suggested (amended by #85)**: the daily Ghosted suggestion (ADR-0050) creates a suggested
  task and never calls this use case; the user applies it by moving the application to `Ghosted` through this use
  case as `Actor.User`, so no status changes by itself and the history names the user.
- The reason of a declined or rejected application is edited by moving to the same status with the new
  reason, which leaves a history entry, rather than through the details.
- A new status or move is a change to `ApplicationStatus`, this ADR's table and `ApplicationStatusTest`,
  plus the enum checks in the migration (a new migration, never an edited one). A new move needs no
  migration: the matrix lives only in the domain.
- After a status change, a client holding an open details form gets 409 on save (the version moved on) and
  must reload (#103).
