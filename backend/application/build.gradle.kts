// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Use cases and ports. Depends on the domain only; no frameworks.

plugins {
    id("jofi.kotlin-conventions")
    id("jofi.kover-conventions")
}

dependencies {
    api(project(":domain"))
}
