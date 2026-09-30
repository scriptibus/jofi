<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0022: Trunk-based development: one issue, one agent, one PR

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.3

## Context

Several agents work in parallel on the same repository.

## Decision

`main` is protected and always releasable. One issue → one branch (`agent/<issue>-<slug>`) → one agent → one PR, squash-merged with a Conventional Commit title. Issues are contracts (goal, acceptance criteria, spec section, contexts in scope, out of scope). Each milestone starts with a contracts PR (domain model, ports, migration, OpenAPI shape). Only one open PR at a time may add Flyway migrations. Soft limit ~400 changed lines.

## Consequences

Parallel agents rarely collide. Branches must be up to date before merge.
