<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0030: Persistence baseline: build-time jOOQ codegen and an append-only changelog

- Status: accepted
- Date: 2026-09-30
- Source: issue #10, PR #49; refines ADR-0008 and ADR-0009

## Context

ADR-0008 and ADR-0009 chose PostgreSQL with pgvector/pg_trgm, Flyway and jOOQ generated from the migrated
schema, but left open the concrete image, how codegen runs, the versions, how the audit trail (spec §13)
is protected and how the "one migration PR at a time" rule is enforced.

## Decision

- **Image:** `pgvector/pgvector:0.8.6-pg18-trixie`, pinned by digest in `backend/gradle.properties`
  (`jofi.postgresImage`). PostgreSQL 18 is the latest major with a pgvector image; the spec's "17" predates
  it and its version note asks for the latest stable. The same image serves codegen, tests and `bootTestRun`.
- **Codegen:** the `generateJooq` task in `adapters/persistence` runs a small generator (own `codegen`
  source set and locked classpath) in a separate JVM: Testcontainers PostgreSQL, Flyway from zero, jOOQ
  `GenerationTool` (Java output, routines excluded). Output goes to `build/` and is never committed, so the
  generated code cannot drift from the migrations; the task is cached on the migration files.
  Docs: https://www.jooq.org/doc/3.21/manual/code-generation/codegen-execution/codegen-programmatic
- **Versions:** jOOQ 3.21.8 and Flyway 13.7.0 override the Spring Boot 4.1.1 BOM (3.21.7, 12.4.0). The
  newest releases younger than seven days are skipped, matching Renovate's `minimumReleaseAge`.
- **Changelog:** one `changelog_entry` table in the `shared` kernel. Actors are stored as kind + name with
  a check constraint; field changes as a JSONB array. A row trigger rejects `UPDATE` and `DELETE`, so the
  application cannot rewrite history; restores replace the table with `TRUNCATE`.
- **Migration lock:** the `migration-lock` CI job fails a PR that adds a migration while an older open PR
  also adds one (oldest PR wins).

## Consequences

- Every backend build and test run needs a Docker-compatible container runtime.
- Schema changes break compilation where the code depends on them.
- The append-only trigger keeps personal data in before/after values until an erasure or redaction path
  exists (#52). That must land before feature PRs write personal data into the changelog.
- Drop the BOM overrides once Spring Boot manages the same or newer versions.
