<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0046: Application sources and job description snapshots: content hash and freezing on applying

- Status: accepted
- Date: 2026-09-30
- Source: spec §6.1 (Sources, Job description history), §8.4 (Duplicates & updates); issue #78 (M1-C2c);
  refines ADR-0041 and builds on ADR-0044

## Context

A job can be found in several places (a scanner, a link the user pastes, text typed or pasted in the chat).
It is one application with several sources, each with its discovery date and original link (spec §6.1).
Each source keeps a history of its job description: a full snapshot at discovery, one on every detected
change, and the version the user applied for, frozen. The text is stored locally so it survives the posting
going offline; a source that disappears is marked "offline" and keeps its snapshots (§8.4). The questions:

- Are sources part of the application aggregate, and is adding one a new version of the application?
- How does Jofi decide that a posting "changed", so fetching the same posting twice stores nothing?
- Where does "frozen" live, when is it set, and how is it kept from changing?
- Posting text and links are untrusted input (threat model T2): how are they stored and shown?

## Decision

### Sources belong to the application, but are not a version of it

`ApplicationSource` (id, kind `SCANNER`/`URL`/`MANUAL_CHAT`, original link as a `WebAddress`, discovery time,
`offlineSince`) is a child entity of the application: `Application.sources` is read with the application
(`findById`, `search`) and the API returns it inside `ApplicationResponse`. It is written only through its own
port (`ApplicationSourceRepositoryPort`), never by the application's detail, status or contact writes (the
"no write overwrites what it does not own" rule of ADR-0041). Adding a source or marking one offline keeps the
application's `version` and `updatedAt`, like read/unread: imports and scanners record sources while the user
edits, and a scanner must not turn the user's next save into a `409`. Each source and each snapshot is its own
changelog entity (`application_source`, `description_snapshot`). An application holds at most 50 sources (a
domain rule; the table does not limit it, and since adds take no version, the repository counts again after
locking the application's row with `SELECT … FOR NO KEY UPDATE`, answering `SourceLimitReached`, so concurrent
adds cannot exceed it). A `URL` source always has a link. The discovery time input may name lies between
2000-01-01 (as `BillingMonth.EARLIEST`) and now (`OUT_OF_RANGE` otherwise); the table has no bound, so it is
never stricter. Links are stored as found and never fetched here; fetching is the URL import's job, through the
SSRF guard (#97). They may carry personal tracking parameters, so changelog entries leave them out and
`WebAddress.toString()` prints only the host. The same link may belong to several
applications (a careers page listing several jobs), so it is indexed (hash index, links are up to 2048
characters) but not unique.

### A description is normalized text with a SHA-256 content hash

`DescriptionText` is the posting's text as found (plain text or Markdown, untrusted, rendered sanitised), in
Unicode NFC, with every line break as `\n` and trimmed, at most 100,000 characters (postings rarely exceed
20,000; the limit keeps whole pages out). Normalizing first means the same posting fetched on two systems, or
once with CRLF, hashes the same. `ContentHash` is SHA-256 over the text's UTF-8 bytes, as 64 lower-case hex
digits: collision-free for this purpose, in the JDK and in PostgreSQL (`sha256()`), with no dependency. The
database checks `content_hash = encode(sha256(convert_to(description, 'UTF8')), 'hex')`, which is exactly what
the domain computes, so a restored or hand-edited row can never claim another text's hash and the check is never
stricter than the domain.

Recording a text for a source (`DescriptionSnapshot.next`) compares its hash with the source's newest snapshot:
the same hash is `Unchanged` (nothing stored, no changelog entry), anything else is a new snapshot. A text that
goes back to an earlier version (A → B → A) is a new version again, so there is no unique constraint on
`(source_id, content_hash)`. The repository locks the source row while reading the newest snapshot, so two
concurrent recordings of the same new text cannot both store it.

### Freezing: a timestamp on the snapshot, set once, in the status change's transaction

The freeze flag lives on each snapshot as `frozen_at` (`null` = not frozen), not on the application: each source
has its own history, and the snapshot is what the user applied for. (Decided by Lucas in the review of PR #152.)

- **When:** a status change freezes when it moves from a status in which the user has not applied
  (`DISCOVERED`, `SHORTLISTED`, `PREPARING`, `DECLINED`) into one in which they have (`APPLIED` and everything
  after it: `INTERVIEWING`, `OFFER`, `ACCEPTED`, `REJECTED`, `WITHDRAWN`, `GHOSTED`).
  `ApplicationStatus.impliesApplied` and `ApplicationStatusChanged.freezesDescriptions` encode it, so skipping
  straight to `INTERVIEWING` (ADR-0044 allows forward skips) freezes too, and reopening within the applied stages
  (e.g. `REJECTED` → `INTERVIEWING`) asks for nothing. `DECLINED` counts as not applied, since the user may
  decline before applying.
- **Only the first freeze counts.** A source that has a frozen snapshot is never frozen again: the freeze
  selects only sources `NOT EXISTS (… frozen_at IS NOT NULL)` (`DescriptionSnapshot.toFreeze` in the domain).
  So `OFFER` → `DECLINED` → `OFFER` keeps what was applied for, even if a change was detected while declined.
  An application declined *before* applying and applied to later has no frozen snapshot yet, so its first
  application freezes the version current then (including a change detected in between).
- **What:** for each such source, its newest snapshot captured at or before the status change's time, set to
  `frozen_at` = that time. A snapshot captured later is never frozen by it.
- **How: synchronously, in the status change's transaction.** #84's `ChangeApplicationStatusUseCase` calls
  `DescriptionSnapshotRepositoryPort.freeze(application, change time)` when the move `freezesDescriptions`, and
  writes one changelog entry per frozen snapshot (entity `description_snapshot`, the status change's actor). The
  status change and its freeze are stored together or not at all: no event can be lost between a commit and a
  listener, so no reconcile job is needed. There is no freeze inbound port; #86 owns recording, listing, reading
  and diffing only.
- **Sources found after applying:** a source added while `application.status.impliesApplied` stores its
  discovery snapshot already frozen (`frozen_at = captured_at`, `DescriptionSnapshot.discovery`), in the add's
  transaction (#96): it is the only record of the posting the user applied to at that place.
- **Kept:** a snapshot never changes. `DescriptionSnapshot.freeze` keeps the first freeze time, and a changed
  text after applying is a new, unfrozen snapshot; the frozen one stays as it was. In the database the trigger
  `application_description_snapshot_immutable` rejects every `UPDATE` except setting `frozen_at` once with
  nothing else changing (the error names it as its constraint, so a repository can map it by name). Snapshots
  are deleted only with their source, and sources with their application (`ON DELETE CASCADE`, behind the
  application delete's confirmation); a restore replaces the table with `TRUNCATE`, which the row trigger does
  not see.
- **Escape hatch for migrations:** a later migration that must rewrite snapshots (e.g. a new normalization)
  runs `ALTER TABLE application_description_snapshot DISABLE TRIGGER application_description_snapshot_immutable`,
  its `UPDATE`, and `ENABLE TRIGGER …` **within the same migration** (one transaction, so the trigger is never
  left off), and keeps `content_hash` consistent in the same statement
  (`content_hash = encode(sha256(convert_to(description, 'UTF8')), 'hex')`, which the check enforces anyway).
  Nothing else ever disables it.

- **A source's first text after applying:** recording the first snapshot of a source that has none while the
  application `impliesApplied` stores it frozen at once (`DescriptionSnapshot.firstOf`), for the same reason as a
  source found after applying: it is the only record of that posting. Every later recording is unfrozen.

### The diff: an in-house line diff with bounded work

The diff (#86) compares whole lines, each keeping its `\n`, so the segments spell both texts exactly, and returns
runs of `UNCHANGED`/`REMOVED`/`ADDED` lines as structured data (never HTML; clients render them sanitised). It is
Myers' O((N+M)·D) algorithm, about 150 lines in the domain (`LineDiff`), rather than a library: the domain takes no
dependencies, the need is one function, and a line diff of trimmed text needs no patch format. Common first and
last lines are set aside first. The work is bounded without a clock: past 1,000 inserted plus deleted lines
(`LineDiff.MAX_EDITS`) the differing middle becomes one removed and one added segment, so even two unrelated texts
at the 100,000-character limit cost at most about (N+M)·1,000 steps. Since stored texts are trimmed, the last line
has no `\n`: a line appended at the end shows the former last line as removed and added again.

### API shape

`POST /api/applications/{id}/sources` (add a source, optionally with its text at discovery, 201),
`POST /api/applications/{id}/sources/{sourceId}/snapshots` (record the current text by hand; the answer says
whether a version was `added`), `GET /api/applications/{id}/sources/{sourceId}/snapshots` (versions without
their texts), `GET /api/applications/{id}/snapshots/{snapshotId}` (one version with its text) and
`GET /api/applications/{id}/description-diff?from=&to=` (segments `UNCHANGED`/`ADDED`/`REMOVED`, between any two
versions of the application's sources; the algorithm is #86's). None of them is a new version of the
application, so none takes `basedOnVersion`; none deletes anything, so none needs a confirmation. A source or
snapshot the application does not have is `404` with `source-not-found` or `snapshot-not-found`.

## Consequences

- Scanners (M4) and imports (#96, #97) add sources and snapshots without conflicting with the user's edits, and
  the change detection is one hash comparison.
- A frozen snapshot is provably what the user applied for: the domain has no way to change it and the database
  refuses to.
- Descriptions can be large; the application list reads sources but never snapshot texts, and the version list
  reads summaries only.
- Merging and splitting applications (M4) moves sources between applications; snapshots follow their source.
- The companies context keeps its own copy of `WebAddress` until it is next touched; the applications context
  uses the shared one (`shared.domain.text.WebAddress`), like the text helpers in ADR-0041.
