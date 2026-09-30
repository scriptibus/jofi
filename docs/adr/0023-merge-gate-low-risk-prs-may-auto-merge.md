<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0023: Merge gate: low-risk PRs may auto-merge

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.4

## Context

Lucas can't review every agent PR, but risky changes need a human.

## Decision

A PR auto-merges only if all CI checks and triggered lenses are green, it touches none of the protected paths (AGENTS.md §8, Renovate patch/minor excepted), and the risk-classifier lens rates it low. The label comes from CI, never from the authoring agent. Everything else waits for Lucas. Implemented in M0 step 3.

## Implementation

- The protected paths live in `.review/protected-paths.json`, read by both the gate and the risk-classifier lens.
- The decision is `.review/gate.py`, unit-tested in CI. The `gate` job in `lenses.yml` (read-only) runs the base branch's copy of it on data CI collects itself: lens results, the risk rating, changed files, check runs, PR metadata. Labels are never an input, and any missing or invalid input means `needs-human`. The result goes into an artifact.
- `merge-gate.yml` runs on `workflow_run`, so always from main's definition, and holds the only write token. It disarms auto-merge when a lenses run starts. When the run completes it validates the artifact as untrusted data and re-checks the PR state and protected paths with main's `gate.py`. Then it either enables squash auto-merge pinned to the evaluated commit, or disables auto-merge and sets `needs-human`. It keeps one summary comment up to date.
- A PR that changes `lenses.yml` controls its own lenses run, but it touches a protected path, so main's re-check never lets it auto-merge.
- The required check `result` waits for the gate's decision. GitHub's ruleset still holds the merge until every required check is green. `needs-human` does not fail `result`, so Lucas can merge by hand.
- Details and known limits: `.review/README.md`.

## Consequences

Lenses, workflows and agent instructions always need a human, so agents can never weaken their own reviewers.
