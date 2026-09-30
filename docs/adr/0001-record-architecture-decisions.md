<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0001: Record architecture decisions

- Status: accepted
- Date: 2026-09-29
- Source: 02-decisions.md; docs/spec/04-tech-stack-proposal.md §4.8

## Context

Jofi is built mostly by coding agents working in parallel. Decisions that live only in chat history get lost, and agents re-decide them differently.

## Decision

Every significant decision is recorded as a short ADR in `docs/adr/` (context, decision, consequences). A new decision in a PR comes with a new ADR; a changed decision supersedes the old ADR rather than editing history. The docs lens checks this.

## Consequences

Agents can look up why things are as they are. ADRs are protected paths, so changing one needs Lucas's review.
