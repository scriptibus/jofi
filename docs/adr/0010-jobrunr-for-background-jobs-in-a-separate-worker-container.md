<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0010: JobRunr for background jobs in a separate worker container

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §7

## Context

Scanners, AI scoring and document generation run in the background with retries, schedules and a visible job log, without extra infrastructure.

## Decision

JobRunr with PostgreSQL storage. The `worker` container runs the same image as `app` with a jobs profile. Recurring jobs implement the scanner schedule from proposal §7.

## Consequences

No Redis or message broker. The job dashboard can be shown to the user.
