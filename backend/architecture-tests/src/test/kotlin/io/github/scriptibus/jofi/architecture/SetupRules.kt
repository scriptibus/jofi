// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.setup.application.AssignTaskModelUseCase
import io.github.scriptibus.jofi.setup.application.CorrectModelCapabilitiesUseCase
import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.application.DeleteProviderUseCase
import io.github.scriptibus.jofi.setup.application.RefreshProviderModelsUseCase
import io.github.scriptibus.jofi.setup.application.UpdateProviderUseCase
import io.github.scriptibus.jofi.shared.domain.Actor

/**
 * Who may change the AI provider setup (#20 security review, #23). The provider config decides where
 * prompts go and feeds the AI transport's allowlist, so only the user's REST API may reach the
 * mutating setup use cases, and only web adapters may speak as the user. The use cases refuse every
 * other actor as well; these rules keep an MCP or chat tool from being wired to them at all.
 */
object SetupRules {
    private val MUTATING_SETUP_USE_CASES =
        listOf(
            CreateProviderUseCase::class.java,
            UpdateProviderUseCase::class.java,
            DeleteProviderUseCase::class.java,
            RefreshProviderModelsUseCase::class.java,
            CorrectModelCapabilitiesUseCase::class.java,
            AssignTaskModelUseCase::class.java,
        )

    /** The setup inbound ports; their implementations live in setup.application. */
    private const val SETUP_INBOUND_PORTS = "$BASE.setup.application.port.inbound.."

    /**
     * Classes besides web adapters that must name [Actor.User], with the reason (nested classes count as
     * their outer class). Every entry needs a human review: each one could act as the user.
     */
    val USER_ACTOR_ALLOWLIST: Map<String, String> =
        mapOf(
            "$BASE.shared.adapter.persistence.ActorColumns" to
                "stores and reads the actor of changelog entries",
            "$BASE.shared.domain.confirmation.ConfirmationBinding" to
                "encodes every actor kind into the confirmation digest; acts as nobody",
            "$BASE.setup.application.SetupChangesKt" to "the guard that lets only the user change the provider setup",
            "$BASE.system.application.PasswordChecksKt" to
                "records the owner's password change, which the current password authenticates",
        )

    /** Only the setup REST API (and its bean wiring) may depend on the mutating setup use cases and ports. */
    val onlyTheWebAdapterChangesTheSetup: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackages("$BASE.setup.adapter.web..", "$BASE.setup.config..", "$BASE.setup.application..")
            .should()
            .dependOnClassesThat(isSetupMutation())
            .because("MCP clients and the AI must never change where prompts go (#20)")
            // A known-good fixture set may contain nothing outside the allowed packages.
            .allowEmptyShould(true)

    /** Only web adapters (the logged-in session) may act as [Actor.User]; see [USER_ACTOR_ALLOWLIST]. */
    val onlyWebAdaptersActAsTheUser: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackage("..adapter.web..")
            .and(DescribedPredicate.not(isAllowlisted()))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(Actor.User::class.java)
            .because("MCP tools, the AI, scanners and jobs act under their own actor, never as the user")

    private fun isSetupMutation(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("mutating setup use cases or setup inbound ports") { type ->
            MUTATING_SETUP_USE_CASES.any { type.isEquivalentTo(it) } ||
                JavaClass.Predicates.resideInAPackage(SETUP_INBOUND_PORTS).test(type)
        }

    private fun isAllowlisted(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("allowlisted to name the user") { type ->
            type.name.substringBefore('$') in USER_ACTOR_ALLOWLIST
        }
}
