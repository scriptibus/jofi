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
    testImplementation(project(":adapters:persistence"))
    testImplementation(project(":adapters:net"))
    testImplementation(project(":adapters:crypto"))
    testImplementation(project(":adapters:jobs"))
    testImplementation(project(":adapters:ai"))
    testImplementation(project(":adapters:backup"))
    testImplementation(project(":adapters:mcp"))
    testImplementation(project(":bootstrap"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.modulith.core)
    testImplementation(libs.archunit)
    testImplementation(libs.konsist)
    // Known-bad fixtures for the JobRunr rules (ADR-0038).
    testImplementation(libs.jobrunr) { exclude(group = "org.jobrunr", module = "jobrunr-bom") }
    // Spring MVC annotations for the known-bad controller fixtures of the confirmation rules (ADR-0039);
    // already on the runtime classpath through bootstrap, version from the Boot BOM.
    testImplementation(libs.spring.web)
    // Fixtures for the AI adapter's SDK exemption (AdapterRulesFixtureTest).
    testImplementation(libs.openai.java.core)
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
