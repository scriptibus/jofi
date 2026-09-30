<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0050: Saved views as versioned filter documents read tolerantly; application settings as an optional single row

- Status: accepted
- Date: 2026-09-30
- Source: spec §6.3 (list with filters and saved views), §6.2 (Ghosted after 14 weeks, configurable), §6.1
  (follow-up rules); issue #81 (M1-C2f); builds on ADR-0041 and the list of #83 (PR #162)

## Context

A saved view is a named filter and order of the application list (#83): text, company, contact, statuses, unread,
languages, source kinds, created/updated ranges, want/fit score ranges, sort and direction. The list's filters will
grow and its rules may tighten (a new filter, a shorter search text, a removed status), while views saved today must
keep opening. A view may name a company or contact, which the user can delete. The Ghosted suggestion (#85) and the
follow-up suggestion (#95) need two configurable periods with the spec's defaults. The questions:

- How is a filter stored so that a view never holds what the list would refuse, and old views survive filter
  changes without a migration rewriting them?
- What happens to a view whose company or contact was deleted?
- Where do the settings live before the user ever changes them, and in backups taken before the table existed?

## Decision

### The filter is the list's search, validated by the list's rules

- `SavedViewFilter` has exactly the fields of `ApplicationSearch` without page and size (a test compares the two
  classes' fields, so a new list filter cannot be forgotten), and its constructor builds the search, so its
  invariants hold. `SavedViewInput.validate()` runs the list's own `ApplicationSearchInput.validate()`; the violations
  keep the list's `SearchField` (`SavedViewField.Filter`), and the API names them `filter.<query parameter>`.
- The API carries the filter as `ApplicationListQuery`, the very type the list binds its query parameters to, so a
  client opens a view by sending its `filter` as the list's parameters.

### Stored as a versioned JSON document, read tolerantly

- `saved_view.filter` is a `jsonb` object in format `filter_version` (1 today), written and read only by
  `SavedViewDocument` (persistence adapter). Its keys are the list's query parameter names; constants are stored by
  name; absent keys are filters not set. The database checks only the shape (an object, a version from 1), never the
  filters, so no filter rule is ever stricter in SQL than in the domain, and a rule change needs no migration.
- Reading is tolerant: unknown keys are ignored; a constant that no longer exists is left out; the rest goes through
  `SavedViewFilter.restore`, which runs the list's validation and leaves out each filter it refuses (a range as a
  whole). A view that lost something comes back `adjusted` (not stored); the UI says so, and saving it again stores
  it as it is now. A view therefore never fails to open and never shows a filter the list refuses.
- A later format adds its version and an upgrade step in the reader (v1 → v2 → ...); rewriting stored JSON in a
  migration is not needed. An unknown (newer) version or an unreadable document is a storage failure of that read.

### Company and contact ids are not foreign keys

A view's company or contact is an id inside the document. It is never checked (the list accepts an unknown id and
lists nothing), and deleting a company or contact neither is blocked by a view nor cascades to it (`SET NULL` would
silently widen the view to every application). After such a delete the view matches nothing for that filter, as
before the delete (a company with applications cannot be deleted; a deleted contact's links are gone); the UI shows
the filter as a deleted company or contact. Only an id stays behind, as in the changelog.

### Names are unique ignoring case

`SavedView.isNamed` compares character by character ignoring case (the same in every locale); the use cases (#99)
refuse a taken name as `InvalidView` (`name`, `TAKEN`). The database's `saved_view_name_unique` only rejects exactly
equal names, which the domain also rejects, so it catches races without being stricter. Deleting a view is a
confirmed delete (`saved-views.delete`, ADR-0039) although it only removes a query: every delete is.

### Application settings: an optional single row

`application_settings` has at most one row (`singleton` primary key). No row means the user never changed them:
`ApplicationSettingsRepositoryPort.find` answers `ApplicationSettings.DEFAULT` (Ghosted after 14 weeks, a follow-up
14 days after applying, version 0), and the first change inserts version 1. So no migration inserts a row, and a
backup from before the table restores to the defaults. Bounds: 1 to 52 weeks, 1 to 90 days, the same in the domain
and the table. Changes are versioned (`basedOnVersion`) and logged with their values (`application_settings`,
id `applications`); they are not personal. The tasks context (#95) reads the follow-up period through a named
interface of the applications context, since it may depend on applications but not on its internals.

## Consequences

- Adding a list filter means adding it to `SavedViewFilter` (the field test fails otherwise), to the document format
  (a new optional key needs no new version) and to `ApplicationListQuery`.
- A tightened list rule never breaks a stored view; the user sees which views were adjusted.
- Views are not updated when a company or contact goes; a view naming a deleted one lists nothing for it.
- The settings table can stay empty forever; readers never assume a row.
