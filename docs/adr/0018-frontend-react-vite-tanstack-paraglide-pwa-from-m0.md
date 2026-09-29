<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0018: Frontend: React, Vite, TanStack, Paraglide, PWA from M0

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §3.2

## Context

The frontend is TypeScript. The UI must be bilingual from day one and installable on phones.

## Decision

React + Vite, TanStack Router and Query, Paraglide JS for i18n (a missing DE/EN key is a compile error), vite-plugin-pwa for install and Web Share Target from M0 (no push notifications). Biome for lint/format, Vitest for unit tests, Playwright for e2e.

## Consequences

PWA install and share sheet need HTTPS; docs recommend Tailscale; default stays localhost-only.
