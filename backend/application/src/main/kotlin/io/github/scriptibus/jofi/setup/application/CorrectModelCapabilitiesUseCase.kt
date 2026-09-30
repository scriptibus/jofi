// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.CorrectModelCapabilitiesPort
import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Stores what the user says a model can do (spec §3.2 capability checks, e.g. weak tool use in a
 * small local model). The profile's source becomes USER, so later refreshes keep it.
 */
class CorrectModelCapabilitiesUseCase(
    private val providers: ProviderConfigPort,
    private val profiles: ModelCapabilityPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CorrectModelCapabilitiesPort {
    override fun execute(
        id: ProviderId,
        input: CapabilityInput,
        actor: Actor,
    ): SetupResult<ModelCapabilityProfile> =
        asUser(actor) {
            input.validate().toSetupResult().then { (model, capabilities) ->
                providers.findById(id).toSetupResult().then {
                    profiles.find(id, model).orNull().then { before ->
                        val profile =
                            ModelCapabilityProfile(id, model, capabilities, CapabilitySource.USER, clock.storedNow())
                        transactions.whenSuccessful { store(before?.capabilities, profile, actor) }
                    }
                }
            }
        }

    private fun store(
        before: ModelCapabilities?,
        profile: ModelCapabilityProfile,
        actor: Actor,
    ): SetupResult<ModelCapabilityProfile> {
        val change = changeOf("capabilities", before?.let(::describe), describe(profile.capabilities))
        val description = "Corrected the capabilities of model ${profile.model.value}"
        val entity = profile.provider.toEntityRef()
        return when {
            profiles.save(profile) !is SetupStoreResult.Success -> {
                SetupResult.StorageFailure("save model")
            }

            !changelog.record(entity, actor, profile.updatedAt, description, listOfNotNull(change)) -> {
                SetupResult.StorageFailure("changelog")
            }

            else -> {
                SetupResult.Success(profile)
            }
        }
    }

    private fun describe(capabilities: ModelCapabilities): String =
        capabilities.supported
            .map { it.toString() }
            .sorted()
            .joinToString(", ")
}
