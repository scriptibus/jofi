<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0027: AI-verifiable UI with a headless browser and a fake AI provider

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.8a; spec §13

## Context

Agents must be able to check the features they build, and e2e tests must not need real keys or internet.

## Decision

Every user-facing feature is verifiable by an agent driving a headless browser: Playwright MCP during development (screenshots in the PR), Playwright e2e in CI against an `e2e` compose profile with a deterministic fake AI provider, WireMock stubs for scanner sources and seeded demo data. Interactive elements have accessible roles and labels; `data-testid` only where none fits; no timing waits.

## Consequences

UI work isn't done until verified this way.
