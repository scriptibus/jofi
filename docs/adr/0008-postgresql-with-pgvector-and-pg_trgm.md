<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0008: PostgreSQL with pgvector and pg_trgm

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §3.3

## Context

We need relational data, full-text/fuzzy matching for duplicate detection and vector search for embeddings, in a single-user Docker setup.

## Decision

PostgreSQL (latest major at M0 step 2) with the pgvector and pg_trgm extensions. No separate vector database.

## Consequences

One database to back up and export. Testcontainers runs the same image in tests.
