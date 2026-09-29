---
name: privacy
title: Privacy
triggers: ["backend/**", "frontend/src/**", "compose*.yaml", "**/*.properties", "**/*.yml", "**/*.yaml"]
blocking: ["high"]
---
<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Lens: privacy

Jofi holds CVs, Arbeitszeugnisse, salary history and contact persons. Only the AI provider the user
configured may receive data, and never the entries flagged "never send to AI" (spec §2, §4.1, §13).

Look for:
- Data reaching an AI provider, an MCP tool result or a prompt without passing the "never send to AI"
  filter in the application layer. (high)
- Personal data, CV content, API keys, tokens or passwords written to logs, exceptions, metrics or error
  responses. (high)
- Any telemetry, analytics, crash reporting, remote fonts/CDNs or third-party calls that aren't the configured
  AI provider or a user-configured scanner source. (high)
- Secrets stored unencrypted, returned by an API, or sent to the frontend. (high)
- New personal-data fields without a way to delete them or without export coverage. (medium)

Ignore: general security bugs (the security lens covers them), code style.

Bad: `log.info("Scoring posting for {}", knowledge.profile)`.
Good: `log.info("Scoring posting {}", posting.id)`.
