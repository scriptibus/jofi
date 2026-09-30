// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// ktlint formatting plus the SPDX header on every Kotlin source and Gradle Kotlin script.
// `spotlessCheck` runs on `check`; `./gradlew spotlessApply` fixes everything it reports.

plugins {
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
        licenseHeader(SpdxHeader.TEXT, SpdxHeader.DELIMITER)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
        licenseHeader(SpdxHeader.TEXT, SpdxHeader.DELIMITER)
    }
}
