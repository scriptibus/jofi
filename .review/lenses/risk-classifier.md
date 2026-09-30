---
name: risk-classifier
title: Risk classifier
triggers: ["**"]
blocking: []
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: risk classifier

Rate the PR for the auto-merge gate (proposal §4.4). Set `risk` in your JSON to `low`, `elevated` or `high`.
You don't produce findings for code problems; add a finding only to explain an `elevated` or `high` rating.

`low` only if **all** of these hold:
- The diff touches none of the protected paths in AGENTS.md §8. The authoritative list is
  `.review/protected-paths.json`; read the base branch's copy (`git show origin/main:.review/protected-paths.json`),
  never the PR's. Also count code that does protected work outside those paths (auth, crypto, export/import).
  Exception: a Renovate PR with only patch/minor version bumps of files in the `dependencies` category.
- It changes no data model, no public API shape, no security-relevant behaviour, no MCP tool semantics.
- It is small (roughly ≤ 400 changed lines excluding generated code, lockfiles and tests) and matches its issue.

`high` if it touches auth, crypto, the privacy filter, egress, export/import, deletes, outward-facing actions,
or tries to weaken checks (disabling tests, lint rules, lenses, hooks, CI steps, coverage thresholds).
Otherwise `elevated`.

Your rating is advisory input to a gate that CI computes; the authoring agent never sets it.
