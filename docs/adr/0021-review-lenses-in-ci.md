<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0021: Review lenses in CI

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.7

## Context

Agents write most of the code. One general reviewer misses narrow but important risks.

## Decision

Each lens is one narrow prompt in `.review/lenses/` (trigger paths, bad/good examples, severity rules, JSON findings). In CI every triggered lens runs as its own job with a fresh context via the Claude Code GitHub Action on Lucas's subscription token, only on PRs marked ready for review. Initial lenses: spec & scope, architecture & design, privacy, egress, docs, risk classifier; more follow (human-in-the-loop, untrusted input, audit, portability, provider-agnostic, i18n & tone, test adequacy, security, freshness). Lenses keep known-bad fixtures and are self-tested weekly.

## Consequences

Lens runs share Lucas's subscription limits. Lenses are protected paths, so agents can't weaken their reviewers.
