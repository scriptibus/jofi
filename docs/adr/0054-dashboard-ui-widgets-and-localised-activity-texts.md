<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0054: Dashboard UI: one widget per figure, activity texts localised from type and verb

- Status: accepted
- Date: 2026-10-01
- Source: spec §10.1 (dashboard); issue #114 (M1-11b1), PR #205; builds on ADR-0052

## Context

ADR-0052 gives the dashboard one endpoint per area, each able to fail on its own. Every recent-activity entry has
an entity type and a **description**, a fixed English text that the code writes. Examples are "Created application",
"Edited task; also changed: title" and "Deleted with its company". No machine-readable action code comes with it.
The UI must show every string in German and English (Paraglide), so the description cannot be shown as it is.

## Decision

- **One widget per figure.** Tasks, pipeline, funnel, AI cost and recent activity each run their own query, marked
  `errorHandledLocally`. Each widget shows its own loading state, empty state and failure with a retry, so no failure
  becomes a global notice. Pipeline and funnel share one cached request. The countdowns widget (#115) is the first
  cell of the grid.
- **Activity sentences are built in the UI** from two parts:
  - the **entity type**, which becomes a localised noun ("Application", "Task", …);
  - the description's **first word**, which becomes a localised verb (`Created` → "created", `Completed` → "done",
    `Dismissed` → "dismissed", …).
  
  Two cases are special: an application's status change ("Status changed") and a task suggestion ("Suggestion …").
  An unknown verb reads as "… changed", and an unknown entity type reads as "Entry". The English description is
  never shown, and neither are field names: the application's timeline, linked from the entry, has the details.
- No backend change: the dashboard depends only on the API contract of ADR-0052.

## Consequences

- The verb mapping (`frontend/src/app/dashboard/dashboard.ts`) relies on the code's wording of changelog
  descriptions: they start with a past-tense verb. A description with a new first verb still renders, as "… changed".
  Unit tests pin the mapping for the descriptions in use today.
- If this becomes fragile (more verbs, other languages of descriptions), the sturdier fix is a structured action
  code on `ActivityEntryResponse`. Then the UI maps that code and the verb mapping goes away.
- One slow or failing endpoint never blanks the dashboard.
