// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK 25 toolchain when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Outdated-dependency report: `./gradlew dependencyUpdates` (not part of `check`).
    id("io.github.ben-manes.versions.settings") version "0.64.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

gradle.rootProject {
    tasks.withType(DependencyUpdatesTask::class.java).configureEach {
        // Also report newer versions for BOM-managed (versionless) dependencies.
        checkConstraints = true
    }
}

rootProject.name = "jofi-backend"

include(
    "domain",
    "application",
    "adapters:web",
    "adapters:persistence",
    "bootstrap",
    "architecture-tests",
)
