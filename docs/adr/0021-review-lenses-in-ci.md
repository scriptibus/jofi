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

## Amendment 2026-09-30: reuse results while the diff is unchanged (#72)

Rebases, "update branch" merges and title/body edits reran every lens although nothing they review had changed,
which used up quota. A lens now runs again only when a fingerprint of its input changes: the PR's diff from the
merge base (only blob ids removed; hunk positions, gitlinks and attributes from the base commit included), the
base branch name, the shared review setup and reuse rules on the base commit, and the lens's own definition.
Otherwise the recorded structured output is replayed through the same evaluation, so failures stay failures and
every event still produces `result` and `merge-gate` for the head commit. Records are written only by main's
`lens-record.yml`, which recomputes the fingerprint itself; lenses accept no record from any other workflow. A
manual re-run never reuses. Details: `.review/README.md`, "Reusing lens results".

Trade-off: after a rebase a lens doesn't see newer versions of files outside the diff, and description edits no
longer trigger a review. A push or a manual re-run gets a fresh one. A rebase over main commits that shift lines
in a file the PR changes reruns the lenses: placement is part of what was reviewed.
