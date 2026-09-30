<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0005: Bounded contexts as packages, verified by Spring Modulith

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §4.3

## Context

Features (applications, companies, knowledge, documents, scanners, chat, training, tasks, setup) should be developable in parallel without tangling.

## Decision

Packages follow `io.github.scriptibus.jofi.<context>.<layer>`. Contexts interact only through their application-level API or domain events (Modulith event publication registry). Spring Modulith's `verify()` runs in the architecture tests. `shared` holds the small shared kernel; `system` holds technical endpoints (info, health).

## Consequences

Issues declare which contexts they touch; the spec & scope lens checks it. The base package `io.github.scriptibus.jofi` follows the GitHub owner and can be renamed early if the project moves to its own domain.
