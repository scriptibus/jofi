<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# ADR 0007: Use detekt 2.0 pre-release until it is stable

- Status: accepted
- Date: 2026-09-30
- Source: https://detekt.dev/docs/introduction/compatibility/

## Context

Rule ADR-0028 requires the latest stable version. On 2026-09-30 the latest stable detekt, 1.23.8, supports Kotlin only up to 2.0.21; our Kotlin is 2.4.x. detekt 2.0.0-alpha.6 is built for Kotlin 2.4.10 and JDK 25.

## Decision

Use detekt 2.0.0-alpha.6 (Lucas approved, 2026-09-29) as a documented exception. Renovate doesn't auto-merge detekt updates; a human moves to each new 2.0 pre-release and to 2.0.0 final as soon as it ships.

## Consequences

Possible rule-set changes between alphas. Without this exception we would have no detekt at all.
