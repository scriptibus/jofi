<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0029: Docker Compose deployment, localhost by default

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3.1, §3.2; spec §3.1

## Context

Jofi is self-hosted on laptops, NAS or home servers, with no cloud dependency.

## Decision

Docker Compose (Podman-compatible) with services `app` (API + SPA + MCP + WebSocket), `worker`, `db`, `pdf` (no outbound network), and optional profiles `voice-local`, `llm-local`, `https` (Caddy), `e2e`. The app listens on localhost by default; a login is required whenever it's exposed.

## Consequences

Phone use needs HTTPS (Tailscale recommended, or the Caddy profile).
