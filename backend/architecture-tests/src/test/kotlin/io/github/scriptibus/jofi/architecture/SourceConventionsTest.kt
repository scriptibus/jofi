// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/** Kotlin-level conventions (naming, visibility, immutability), checked on the sources. */
class SourceConventionsTest {
    private val production = Konsist.scopeFromProduction()
    private val jofiClasses = production.classes().filter { it.resideInPackage("${JofiPackages.BASE}..") }

    @Test
    fun `classes in application are use cases`() {
        jofiClasses
            .filter { it.resideInPackage("..application") }
            .assertTrue { it.name.endsWith("UseCase") }
    }

    @Test
    fun `use cases live in application and have exactly one public method`() {
        jofiClasses
            .filter { it.name.endsWith("UseCase") }
            .assertTrue { useCase ->
                useCase.resideInPackage("..application") &&
                    useCase.functions().count { it.hasPublicOrDefaultModifier } == 1
            }
    }

    @Test
    fun `interfaces in application port are named Port`() {
        production
            .interfaces()
            .filter { it.resideInPackage("..application.port..") }
            .assertTrue { it.name.endsWith("Port") }
    }

    @Test
    fun `controllers only receive use cases`() {
        jofiClasses
            .filter { it.name.endsWith("Controller") }
            .assertTrue { controller ->
                controller.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .all { it.type.name.endsWith("UseCase") }
            }
    }

    @Test
    fun `domain data and value classes are immutable`() {
        jofiClasses
            .filter { it.resideInPackage("..domain..") && (it.hasDataModifier || it.hasValueModifier) }
            .assertFalse { domainType ->
                domainType.properties().any { it.isVar } ||
                    domainType.primaryConstructor
                        ?.parameters
                        .orEmpty()
                        .any { it.isVar }
            }
    }

    @Test
    fun `domain has no lateinit`() {
        production
            .files
            .filter { it.hasPackage("..domain..") }
            .assertFalse { file ->
                file.properties().any { it.hasLateinitModifier } ||
                    file.classes().any { type -> type.properties().any { it.hasLateinitModifier } }
            }
    }
}
