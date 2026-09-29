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

## Consequences

Lenses, workflows and agent instructions always need a human, so agents can never weaken their own reviewers.
