<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0028: Always use current versions and current documentation

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.10

## Context

Agents' built-in knowledge ends at their training cutoff; without a rule we would build on outdated versions and deprecated APIs.

## Decision

Before adding anything new, agents look up the latest stable version at the official source and read the current official docs (Context7 or the docs site). Deprecated APIs are not used. PR descriptions list every new or bumped dependency with version and doc link. Enforced by the freshness lens, deprecation warnings as errors, outdated-dependency reports, Renovate and OpenRewrite recipes.

## Consequences

Slightly slower when adding dependencies; far fewer upgrade surprises. Exceptions (for example ADR-0007) need an ADR.
