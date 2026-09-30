// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Applied only to the root project: formats and header-checks the build scripts and the
// build-logic sources, which no module convention covers.

plugins {
    base
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion

spotless {
    kotlin {
        target("build-logic/src/**/*.kt")
        ktlint(ktlintVersion)
        licenseHeader(SpdxHeader.TEXT, SpdxHeader.DELIMITER)
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(ktlintVersion)
        licenseHeader(SpdxHeader.TEXT, SpdxHeader.DELIMITER)
    }
}
