<!--
SPDX-FileCopyrightText: 2026 Jofi contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# domain

Owns the business model of every bounded context: entities, value objects, domain services,
domain events and sealed result types. Packages: `io.github.scriptibus.jofi.<context>.domain`.

Rules:
- Kotlin stdlib only. No Spring, jOOQ, JPA, Jackson or any other library (the build has none).
- No `lateinit`, no `!!`, no magic numbers; data and value classes only have `val`s.
- Validate invariants in `init` blocks or factory functions; express expected failures as
  sealed result types, not exceptions that cross ports.
- No I/O, no clock or randomness access; pass such values in.
- Every public behaviour has a plain JUnit + Kotest unit test. Kover gate: >= 70 % lines.
