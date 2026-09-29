// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.scriptibus.jofi.architecture.JofiPackages.ADAPTER
import io.github.scriptibus.jofi.architecture.JofiPackages.APPLICATION
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.architecture.JofiPackages.CONFIG
import io.github.scriptibus.jofi.architecture.JofiPackages.DOMAIN
import org.junit.jupiter.api.Test

/** Hexagonal layering and bounded-context boundaries, checked on the compiled classes. */
class LayerDependencyTest {
    private val classes = JofiClasses.production

    @Test
    fun `every class follows the package convention base-context-layer`() {
        classes()
            .should()
            .haveNameMatching(PACKAGE_CONVENTION)
            .because("code lives in $BASE.<context>.(domain|application|adapter.<kind>|config)")
            .check(classes)
    }

    @Test
    fun `domain and application are free of frameworks`() {
        noClasses()
            .that()
            .resideInAnyPackage(DOMAIN, APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*FRAMEWORK_PACKAGES)
            .because("only adapters and bootstrap may use Spring, jOOQ, JPA or Jackson")
            .check(classes)
    }

    @Test
    fun `layers only depend inwards`() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(DOMAIN_LAYER)
            .definedBy(DOMAIN)
            .layer(APPLICATION_LAYER)
            .definedBy(APPLICATION)
            .layer(ADAPTER_LAYER)
            .definedBy(ADAPTER)
            .layer(CONFIG_LAYER)
            .definedBy(CONFIG)
            .whereLayer(CONFIG_LAYER)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(ADAPTER_LAYER)
            .mayOnlyBeAccessedByLayers(CONFIG_LAYER)
            .whereLayer(APPLICATION_LAYER)
            .mayOnlyBeAccessedByLayers(ADAPTER_LAYER, CONFIG_LAYER)
            .whereLayer(DOMAIN_LAYER)
            .mayOnlyBeAccessedByLayers(APPLICATION_LAYER, ADAPTER_LAYER, CONFIG_LAYER)
            .check(classes)
    }

    @Test
    fun `adapters do not depend on other adapters`() {
        slices()
            .matching("$BASE.(*).adapter.(*)..")
            .should()
            .notDependOnEachOther()
            .because("adapters talk to each other only through use cases and ports")
            .check(classes)
    }

    @Test
    fun `bounded contexts are free of cycles`() {
        slices()
            .matching("$BASE.(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
    }

    private companion object {
        const val DOMAIN_LAYER = "Domain"
        const val APPLICATION_LAYER = "Application"
        const val ADAPTER_LAYER = "Adapter"
        const val CONFIG_LAYER = "Config"

        val FRAMEWORK_PACKAGES =
            arrayOf(
                "org.springframework..",
                "org.jooq..",
                "jakarta.persistence..",
                "jakarta.inject..",
                "tools.jackson..",
                "com.fasterxml.jackson..",
            )

        /** `<base>.<context>.<layer>.…` or a top-level class in the base package (the app). */
        val PACKAGE_CONVENTION =
            Regex.escape(BASE) +
                "\\.([a-z][a-z0-9]*\\.(domain|application|adapter\\.[a-z][a-z0-9]*|config)\\..+|[A-Z][A-Za-z0-9]*)"
    }
}
