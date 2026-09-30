<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0016: OpenAPI contract with generated TypeScript client

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3

## Context

The frontend should get compile-time safety from the backend's types, and breaking API changes must be visible.

## Decision

springdoc-openapi publishes the spec; orval generates types, Zod schemas and TanStack Query hooks. CI fails if the generated client is stale; oasdiff flags breaking changes.

## Consequences

API changes show up as type errors in the frontend.
