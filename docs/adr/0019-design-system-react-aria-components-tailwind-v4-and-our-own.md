<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0019: Design system: React Aria Components, Tailwind v4 and our own tokens

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §3.4

## Context

Jofi should look distinctive and be extended consistently by agents, with WCAG-grade accessibility and locale-aware inputs.

## Decision

Interaction and accessibility come from React Aria Components (unstyled). Styling is Tailwind CSS v4 driven only by our design tokens (CSS custom properties). Our component library lives in `frontend/src/ui` and is documented in Storybook with the a11y addon. Feature code may use only `ui` components and tokens. Rejected: MUI/Mantine (generic look), shadcn/ui (Radix; React Aria is stronger on a11y and i18n), fully hand-written components.

## Consequences

Lint rules forbid raw colours, arbitrary values and direct primitive imports outside `ui`. Visual regression and axe checks run in e2e.
