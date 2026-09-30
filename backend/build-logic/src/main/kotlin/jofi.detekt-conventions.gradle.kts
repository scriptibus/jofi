// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// detekt 2.0 (pre-release, documented exception in backend/AGENTS.md). The rules live in
// backend/config/detekt/detekt.yml; any finding fails the build.

import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
    basePath.set(isolated.rootProject.projectDirectory)
}

// The plugin wires only the plain `detekt` task (no type resolution) into `check`. Also run the
// type-resolved tasks, because several rules we rely on (e.g. UnsafeCallOnNullableType) need it.
tasks.named("check") {
    dependsOn(tasks.withType<Detekt>().matching { it.name in setOf("detektMain", "detektTest") })
}
