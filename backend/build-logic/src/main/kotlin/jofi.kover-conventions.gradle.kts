// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Coverage gate for the core modules (domain, application): `koverVerify` runs on `check` and
// fails below the minimum line coverage. Adapters and bootstrap are covered by slice and
// integration tests instead.

plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        verify {
            rule {
                minBound(CoverageGate.MIN_LINE_COVERAGE_PERCENT)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
