<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0009: Flyway migrations and jOOQ, no JPA

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §4.3

## Context

Schema changes should break compilation where they matter, and the domain must stay free of persistence annotations and lazy-loading traps.

## Decision

Flyway migrations (timestamp versions) define the schema. jOOQ code is generated from the migrated schema (Testcontainers at build time). Repositories in `adapters/persistence` map between jOOQ records and domain objects.

## Consequences

Type-safe SQL, explicit mapping code. Migrations are a serialized hot spot: only one open PR may add migrations at a time (CI check).
