// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.DeleteProviderPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import java.time.Clock

/**
 * Removes a provider in two steps (ADR-0039), with its key and its model capabilities. The
 * confirmation is bound to the provider's id and name, read in the same transaction as the removal.
 * A provider with assigned tasks is refused before a token is issued.
 */
class DeleteProviderUseCase(
    private val providers: ProviderConfigPort,
    private val assignments: ModelAssignmentPort,
    private val secrets: SecretStorePort,
    private val confirmation: ConfirmActionUseCase,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : DeleteProviderPort {
    override fun execute(
        id: ProviderId,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): SetupResult<Unit> =
        asUser(requester.actor) {
            transactions.whenSuccessful {
                providers.findById(id).toSetupResult().then { provider ->
                    unassigned(provider).then { confirmThenDelete(provider, requester, token) }
                }
            }
        }

    private fun unassigned(provider: ProviderConfig): SetupResult<Unit> =
        assignments.findAll().toSetupResult().then { assigned ->
            if (assigned.any { it.provider == provider.id }) SetupResult.InUse else SetupResult.Success(Unit)
        }

    private fun confirmThenDelete(
        provider: ProviderConfig,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): SetupResult<Unit> {
        val action =
            ConfirmableAction(
                ProviderId.DELETE_OPERATION,
                listOf(provider.id.value.toString()),
                ConfirmationEffect(ProviderId.ENTITY_TYPE, provider.displayName),
            )
        return when (val outcome = confirmation.execute(ConfirmationRequest(requester, action, token))) {
            is ConfirmationResult.Confirmed -> delete(provider, outcome, requester)
            is ConfirmationResult.Unconfirmed -> SetupResult.Unconfirmed(outcome)
        }
    }

    private fun delete(
        provider: ProviderConfig,
        proof: ConfirmationResult.Confirmed,
        requester: ConfirmationRequester,
    ): SetupResult<Unit> {
        val key = provider.apiKey
        val changes = providerChanges(provider, null)
        return providers.delete(provider.id, proof).toSetupResult().then {
            // The provider row referenced the secret, so the key can only go after it.
            when {
                key != null && secrets.delete(key) is SecretResult.StorageFailure -> {
                    SetupResult.StorageFailure("delete key")
                }

                !changelog.record(
                    provider.id.toEntityRef(),
                    requester.actor,
                    clock.storedNow(),
                    "Removed AI provider",
                    changes,
                ) -> {
                    SetupResult.StorageFailure("changelog")
                }

                else -> {
                    SetupResult.Success(Unit)
                }
            }
        }
    }
}
