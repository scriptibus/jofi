// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Architecture rules (ArchUnit, Konsist, Spring Modulith) over all production code.

plugins {
    id("jofi.kotlin-conventions")
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":adapters:web"))
    testImplementation(project(":bootstrap"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.core)
    testImplementation(libs.archunit)
    testImplementation(libs.konsist)
}

tasks.withType<Test>().configureEach {
    // Konsist reads sources from disk: re-run whenever any module's Kotlin sources change.
    inputs
        .files(
            fileTree(isolated.rootProject.projectDirectory) {
                include("*/src/**/*.kt", "adapters/*/src/**/*.kt")
            },
        ).withPropertyName("backendSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
