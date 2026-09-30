<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0026: AGPL-3.0 licence and dependency licence allowlist

- Status: accepted
- Date: 2026-09-29
- Source: docs/spec/04-tech-stack-proposal.md §4.6a

## Context

Lucas wants no commercial exploitation without giving back, and contributions to flow back.

## Decision

Jofi is AGPL-3.0-or-later. Dependencies must be compatible: MIT, Apache-2.0, BSD-2/3, ISC, MPL-2.0, LGPL, EPL-2.0 (with care), GPL-3.0, AGPL-3.0 (plus permissive font/data licences such as OFL-1.1, CC0-1.0). Anything else fails until a human approves. Enforced by licensee (Gradle), a pnpm licence check, dependency-review-action and Trivy for images. Source files carry SPDX headers, checked by REUSE.

## Consequences

Some libraries are off-limits (SSPL, Commons Clause, GPL-2.0-only).
