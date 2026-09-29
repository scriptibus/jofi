<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0024: Quality pipeline with identical local and CI commands

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.5, §4.6b

## Context

Agents should see the same result locally that CI will produce, as early and cheaply as possible.

## Decision

Cheapest checks first: Claude Code hooks (format + lint on edit, affected tests before finishing), lefthook pre-commit (Spotless, gitleaks, Biome), then CI on every PR (build with dependency verification, detekt, ktlint, architecture tests, Modulith verify, unit + integration tests, Flyway from zero + jOOQ drift, OpenAPI drift + oasdiff, Kover diff coverage, export/import round-trip, frontend lint/types/tests, Docker build, Playwright smoke, AI tests on recorded fixtures). Nightly: full e2e, live-provider evals, PIT mutation testing, lens self-tests, docs drift. `./gradlew check` and `pnpm check` run the same checks locally.

## Consequences

Most problems surface in the agent loop, before CI.
