// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.ClearModelPricePort
import io.github.scriptibus.jofi.setup.domain.CapabilityInput
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Removes the price of a model (spec §3.2); its later calls have an unknown cost again, while the cost
 * entries already recorded stay as they are (ADR-0055). Only the user may do it. Removing a setting the
 * user can enter again destroys no data, so it takes no confirmation step (like removing the monthly cap);
 * it lands in the changelog. A model without a price succeeds and writes nothing.
 */
class ClearModelPriceUseCase(
    private val providers: ProviderConfigPort,
    private val prices: ModelPricePort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : ClearModelPricePort {
    override fun execute(
        id: ProviderId,
        model: String,
        actor: Actor,
    ): SetupResult<Unit> =
        asUser(actor) {
            CapabilityInput.modelName(model).toSetupResult().then { name ->
                openAiCompatibleProvider(providers, id).then {
                    prices.find(id, name).orNull().then { before ->
                        if (before == null) {
                            SetupResult.Success(Unit)
                        } else {
                            transactions.whenSuccessful { remove(before, actor) }
                        }
                    }
                }
            }
        }

    private fun remove(
        before: ModelPriceOverride,
        actor: Actor,
    ): SetupResult<Unit> {
        val description = "Removed the price of model ${before.model.value}"
        return when {
            prices.clear(before.provider, before.model) !is SetupStoreResult.Success -> {
                SetupResult.StorageFailure("clear model price")
            }

            !changelog.record(
                before.provider.toEntityRef(),
                actor,
                clock.storedNow(),
                description,
                priceChanges(before, null),
            ) -> {
                SetupResult.StorageFailure("changelog")
            }

            else -> {
                SetupResult.Success(Unit)
            }
        }
    }
}
