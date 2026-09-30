// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption

/** Package names shared by the architecture tests. */
object JofiPackages {
    const val BASE = "io.github.scriptibus.jofi"
    const val DOMAIN = "..domain.."
    const val APPLICATION = "..application.."
    const val PORT = "..application.port.."
    const val ADAPTER = "..adapter.."
    const val CONFIG = "..config.."
}

/** All production classes of the backend (every Gradle module), imported once per test run. */
object JofiClasses {
    val production: JavaClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(JofiPackages.BASE)
    }
}
