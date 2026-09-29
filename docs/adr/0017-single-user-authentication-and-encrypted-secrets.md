<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0017: Single-user authentication and encrypted secrets

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3; spec §3.1

## Context

Jofi is single-user and listens on localhost by default, but must be safe when exposed on a network.

## Decision

Spring Security with one user, argon2id password hashing and a session cookie. API keys are encrypted with Tink (AES-GCM) using a master key generated into the data volume.

## Consequences

No multi-tenancy. Auth and crypto code are protected paths.
