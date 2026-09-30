// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.setup.application.port.AiProviderPort

/**
 * Adapter rules shared by the production check and the known-bad fixture tests
 * ([AdapterRulesFixtureTest]), so both evaluate exactly the same rule.
 */
object AdapterRules {
    /** Generated jOOQ code of the whole schema lives in the shared kernel's persistence adapter (ADR-0032). */
    const val GENERATED_JOOQ = "$BASE.shared.adapter.persistence.jooq.."

    /**
     * Adapters talk to each other only through use cases and ports. One narrow exemption: every
     * context's persistence adapter may use the generated jOOQ code, which is generated for the whole
     * schema into one package. Other adapter kinds (web, ai, ...) still may not touch it.
     */
    val adaptersAreIndependent: ArchRule =
        slices()
            .matching("$BASE.(*).adapter.(*)..")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(resideInAPackage("..adapter.persistence.."), resideInAPackage(GENERATED_JOOQ))
            .because("adapters talk to each other only through use cases and ports")

    /**
     * Only the AI gateway and the provider adapter (`setup.adapter.ai`) may use [AiProviderPort];
     * everything else calls the task-based `LlmPort`/`EmbeddingPort`, so no AI call can bypass
     * routing, the "never send to AI" filter or metering (ADR-0032).
     */
    val onlyTheAiAdapterUsesAiProviderPort: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackage("$BASE.setup.adapter.ai..")
            .and(DescribedPredicate.not(isTheProviderPort()))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(AiProviderPort::class.java)
            .because("callers use the task-based LlmPort/EmbeddingPort behind the AI gateway")

    private fun isTheProviderPort(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("AiProviderPort itself") { it.isEquivalentTo(AiProviderPort::class.java) }
}
