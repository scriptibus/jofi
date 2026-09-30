<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0006: Clean-code rules enforced by detekt, ktlint and architecture tests

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.2

## Context

Lucas wants clean-code style rules, in a lighter form, that agents can't quietly ignore.

## Decision

detekt (build fails): functions ≤ ~30 lines, cyclomatic complexity ≤ 10, ≤ 5 parameters, class size and nesting limits, no `!!`, no generic catch outside adapters, no wildcard imports, no magic numbers in `domain`. ktlint via Spotless formats. Domain errors are sealed result types. Encouraged patterns: Repository, Factory, Strategy, Adapter, Command/use case, Domain events, State.

## Consequences

Rules are mechanical, so review can focus on design. Exceptions need a documented suppression with a reason.
