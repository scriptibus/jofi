---
name: docs
title: Documentation
triggers: ["**"]
blocking: []
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: docs

Documentation is part of the change (proposal §4.8). This lens is advisory; it never blocks.

Look for:
- A new decision (library, pattern, data format, trade-off) without an ADR in `docs/adr/`. (medium)
- A module whose responsibilities or rules changed without an update to its `AGENTS.md`. (medium)
- New or changed MCP tools, REST endpoints, config keys, env variables or compose services not documented. (medium)
- New or bumped dependencies not listed in the PR description with version and doc link. (medium)
- README or spec statements that the diff makes false. (low)

Ignore: missing comments inside code, typos.
