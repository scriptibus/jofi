// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.scriptibus.jofi.architecture.JofiPackages.ADAPTER
import io.github.scriptibus.jofi.architecture.JofiPackages.API_METADATA
import io.github.scriptibus.jofi.architecture.JofiPackages.APPLICATION
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.architecture.JofiPackages.CONFIG
import io.github.scriptibus.jofi.architecture.JofiPackages.DOMAIN
import io.github.scriptibus.jofi.architecture.JofiPackages.SPI_METADATA
import org.junit.jupiter.api.Test

/** Hexagonal layering and bounded-context boundaries, checked on the compiled classes. */
class LayerDependencyTest {
    private val classes = JofiClasses.production

    @Test
    fun `every class follows the package convention base-context-layer`() {
        classes()
            .should()
            .haveNameMatching(PACKAGE_CONVENTION)
            .because(
                "code lives in $BASE.<context>.(domain|application|adapter.<kind>|config); " +
                    "a context root may only hold its Spring Modulith ModuleMetadata",
            ).check(classes)
    }

    @Test
    fun `domain and application are free of frameworks`() {
        noClasses()
            .that()
            .resideInAnyPackage(DOMAIN, APPLICATION)
            .and()
            .haveNameNotMatching(SPI_METADATA)
            .and()
            .haveNameNotMatching(API_METADATA)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*FRAMEWORK_PACKAGES)
            .because("only adapters and bootstrap may use Spring, jOOQ, JPA or Jackson")
            .check(classes)
    }

    /**
     * The exceptions to the rule above: the `ModuleMetadata` (in bootstrap) that makes the companies and
     * applications SPI packages (ADR-0041) and the API packages (#85, #96) Spring Modulith named
     * interfaces may use Spring Modulith, nothing else.
     */
    @Test
    fun `named interface metadata only uses Spring Modulith`() {
        classes()
            .that()
            .haveNameMatching(SPI_METADATA)
            .or()
            .haveNameMatching(API_METADATA)
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("org.springframework.modulith..", "java..", "kotlin..", "org.jetbrains.annotations..")
            .check(classes)
    }

    /** ADR-0041: dependencies run applications -> companies; companies declares ports the applications implement. */
    @Test
    fun `the companies context never depends on the applications context`() {
        noClasses()
            .that()
            .resideInAPackage("$BASE.companies..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$BASE.applications..")
            .check(classes)
    }

    /** ADR-0041, ADR-0049: dependencies run tasks -> companies; companies declares ports tasks implement. */
    @Test
    fun `the companies context never depends on the tasks context`() {
        noClasses()
            .that()
            .resideInAPackage("$BASE.companies..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$BASE.tasks..")
            .check(classes)
    }

    /** ADR-0041: dependencies run tasks -> applications; applications declares ports the tasks context implements. */
    @Test
    fun `the applications context never depends on the tasks context`() {
        noClasses()
            .that()
            .resideInAPackage("$BASE.applications..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$BASE.tasks..")
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
        AdapterRules.adaptersAreIndependent.check(classes)
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

        /**
         * `<base>.<context>.<layer>.…`, a context's Modulith metadata (`<base>.<context>.ModuleMetadata`)
         * or a top-level class in the base package (the app).
         */
        val PACKAGE_CONVENTION =
            Regex.escape(BASE) +
                "\\.([a-z][a-z0-9]*\\.(domain|application|adapter\\.[a-z][a-z0-9]*|config)\\..+" +
                "|[a-z][a-z0-9]*\\.ModuleMetadata|[A-Z][A-Za-z0-9]*)"
    }
}
