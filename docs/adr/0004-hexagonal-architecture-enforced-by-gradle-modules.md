<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0004: Hexagonal architecture enforced by Gradle modules

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §4.1, §4.2

## Context

Architecture rules that are only written down drift, especially with many agents.

## Decision

The backend is split into Gradle modules `domain` → `application` → `adapters/*` → `bootstrap`. `domain` depends only on the Kotlin stdlib, `application` only on `domain`, adapters on `application`; only `bootstrap` sees everything. Architecture tests in `architecture-tests` (ArchUnit + Konsist) check what Gradle can't: annotations, naming, one public method per use case, controllers without logic.

## Consequences

Layer violations fail the build instead of a review. Adding a new adapter means adding a module. Use cases are wired in `bootstrap`, without Spring annotations in `domain`/`application`.
