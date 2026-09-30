<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0012: One MCP tool surface for the built-in chat and external clients

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3, §4.8b; spec §9, §9.1

## Context

The chat operates on all modules, and users may want to use Jofi from Claude Desktop or other MCP clients.

## Decision

Jofi's modules are exposed as tools by a Spring AI MCP server (Streamable HTTP). The built-in chat is an MCP client of that server. External access is a settings toggle, off by default, with revocable per-client bearer tokens and optional read-only tool groups. Deletes and outward actions need a server-enforced confirmation step; knowledge changes become proposals; 'never send to AI' data is never returned.

## Consequences

No second API surface for AI. Safety rules live in the server, so no client can skip them. An MCP contract test suite runs in CI.
