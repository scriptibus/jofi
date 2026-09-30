// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Backup archives (ADR-0042): the zip format with its manifest, unpacking under limits, and the data
// volume's files (knowledge, documents). An export/import protected path (AGENTS.md section 8).

plugins {
    id("jofi.spring-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter)
    implementation(libs.jackson3.databind)
}
