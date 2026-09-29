<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0020: Visual direction B · Stall with user-selectable accent

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/06-design-brief.md

## Context

Three directions were prototyped (A Präzision, B Stall, C Signal).

## Decision

Direction B · Stall: warm stone neutrals, saffron accent, Bricolage Grotesque headings over Onest body text, Geist Mono for data, soft shadows, 4px corners, springy motion, a solid donkey logo that bobs its head while loading. The accent is a user setting from curated presets tuned for light and dark (Saffron default, Cobalt, Teal, Plum, Ink), implemented as a `data-accent` override of `--accent`, `--accent-fg`, `--accent-soft` only. Fonts are self-hosted (no Google Fonts CDN), in line with local-first privacy.

## Consequences

No free colour picker, so contrast stays guaranteed. Light/dark follow the OS with a manual override.
