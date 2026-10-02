<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0056: Bounded lists: offset paging in the use case, note excerpts under their own keys

- Status: accepted
- Date: 2026-10-02
- Source: issue #236 (from the reviews of PR #233 and #239); builds on ADR-0012, ADR-0049, ADR-0053

## Context

The task and interview lists returned every row with its full notes: 300 tasks with 10,000-character notes made
a 3.2 MB answer, 55 interviews with two 50,000-character notes a 5.0 MB one. That overflows a model's context
(MCP) and a phone's screen alike. The interview list also cut at 50 in the adapter and returned the earliest 50, so
the newest were unreachable. A list entry that carried the same keys as the full record also invited a model to
send it back as an update and store a shortened note over the real one.

## Decision

- **Paging lives in the use case.** `PageInput(page, size)` (`shared.domain.paging`): `page` from 0, `size` 1 to 50
  (default 20), out of range is a validation failure naming `page` and `size`. A page answers `PageInfo`:
  `page`, `size`, `total`, `hasMore`. The adapters only translate; limits are domain constants.
- **Offset paging over a stable order.** A list that changes between two reads can repeat or skip an entry; `total`
  shows the change. A cursor would need a stable sort key for the grouped task list, whose groups depend on the
  viewer's zone and the clock, so it was not worth the complexity for one user.
- **The grouped task list pages through its sequence.** Tasks are numbered through the groups in their order
  (soonest first within each), a page is a window of that sequence, and every group is always present in the
  answer, empty ones included. Grouping needs the viewer's zone and "now", so all open tasks are read and grouped,
  then cut. Suggestions are a plain list and are paged in SQL (newest first, then id descending).
- **Lists carry excerpts, not notes.** `TextExcerpt` keeps at most 300 code points, never splits a surrogate pair,
  and says whether it cut. A list entry has no `notes` field at all: `notesExcerpt` and `notesTruncated` are keys
  of their own, in REST and in MCP, so an entry cannot be mistaken for the record, and an update built from one is
  refused by the schema (a required key is missing). The whole text comes from reading one entity (`get_task`,
  `GET /api/tasks/{id}`).

## Consequences

- REST `GET /api/tasks` and `GET /api/tasks/suggestions` take optional `page` and `size`; the Tasks page loads 50 at
  a time and has "Show more". The response gains `page`; list entries lose `notes` for the excerpt keys. A call
  without `page`/`size` now answers the first 20, not everything; the contract check does not see that, and the
  frontend is the only client. A `page` or `size` that is no number answers the `ValidationProblem` naming it.
- The "never send to AI" values (ADR-0053) go out of the **whole** notes before an excerpt is cut, for every list an
  AI reads (`NotesAudience.AI`, `RedactForAiUseCase`): the value filter of the MCP server matches whole values only
  and would no longer recognise one that a cut left half in. The user's own lists (`NotesAudience.USER`) show the
  notes as they are. If the flags cannot be read, an AI's list fails closed: the answer is `privacy-filter-failed`, the
  code every tool uses when the flags cannot be read, and carries no data. An excerpt is cut from the redacted text,
  never inside a redaction marker (it ends before one that does not fit), and a marker is never scanned twice.
  `NotesAudience` is a required parameter and `TaskSummary.of` has no default text, so no caller takes the unfiltered
  notes by omission; nothing yet stops an AI-facing caller from passing `USER` (follow-up #251).
- The done-tasks list (#248) has its own paging shape (`DoneTaskQuery`, no `hasMore`). "Every new list follows
  this" means `PageInput`/`PageInfo`: the done list is aligned to it by whichever of the two pull requests merges
  second.
- Every new list tool follows this: a `page`/`size` pair, the use case limits, excerpts for long text (for an AI,
  cut from filtered text), a `get_*` tool.
