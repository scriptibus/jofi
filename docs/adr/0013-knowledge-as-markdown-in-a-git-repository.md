<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0013: Knowledge as Markdown in a git repository

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §3.3; spec §4

## Context

Knowledge must be human-readable, editable outside Jofi, versioned, and restorable, while scores and search need an index.

## Decision

Knowledge entries are Markdown files in a git repository inside the data volume, managed with JGit. PostgreSQL holds a rebuildable index, embeddings and the proposal queue. Every accepted change is a commit with source and actor.

## Consequences

History and restore come from git. The index must be rebuildable from files at any time.
