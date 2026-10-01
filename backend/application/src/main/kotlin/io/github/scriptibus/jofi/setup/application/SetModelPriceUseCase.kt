// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ModelPricePort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.SetModelPricePort
import io.github.scriptibus.jofi.setup.domain.ModelPriceInput
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Gives a model of an OpenAI-compatible provider a price (spec §3.2), so its calls get a cost instead of
 * "unknown" and count toward the monthly cap. Only the user may price a model: the AI or a scanner could
 * otherwise hide its own spending from the cap. The price applies to calls recorded from now on; the
 * meter is append-only, so earlier entries keep what they recorded (ADR-0055). Setting the same price
 * again writes nothing; every change lands in the changelog in the same transaction.
 */
class SetModelPriceUseCase(
    private val providers: ProviderConfigPort,
    private val prices: ModelPricePort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : SetModelPricePort {
    override fun execute(
        id: ProviderId,
        input: ModelPriceInput,
        actor: Actor,
    ): SetupResult<ModelPriceOverride> =
        asUser(actor) {
            input.validate().toSetupResult().then { valid ->
                openAiCompatibleProvider(providers, id).then {
                    val price =
                        ModelPriceOverride(
                            id,
                            valid.model,
                            valid.inputMicrosPerMillion,
                            valid.outputMicrosPerMillion,
                            clock.storedNow(),
                        )
                    // The old price is read in the transaction that replaces it, so the log shows what it replaced.
                    transactions.whenSuccessful {
                        prices.find(id, valid.model).orNull().then { before -> change(before, price, actor) }
                    }
                }
            }
        }

    private fun change(
        before: ModelPriceOverride?,
        price: ModelPriceOverride,
        actor: Actor,
    ): SetupResult<ModelPriceOverride> =
        if (before != null && before.hasPriceOf(price)) SetupResult.Success(before) else store(before, price, actor)

    private fun store(
        before: ModelPriceOverride?,
        price: ModelPriceOverride,
        actor: Actor,
    ): SetupResult<ModelPriceOverride> {
        val description = "Set the price of model ${price.model.value}"
        return when (prices.save(price)) {
            is SetupStoreResult.Success -> {
                val recorded =
                    changelog.record(
                        price.provider.toEntityRef(),
                        actor,
                        price.updatedAt,
                        description,
                        priceChanges(before, price),
                    )
                if (recorded) SetupResult.Success(price) else SetupResult.StorageFailure("changelog")
            }

            // The provider was deleted after the check above: answer as an unknown provider, not a storage failure.
            SetupStoreResult.NotFound -> {
                SetupResult.NotFound
            }

            else -> {
                SetupResult.StorageFailure("save model price")
            }
        }
    }

    private fun ModelPriceOverride.hasPriceOf(other: ModelPriceOverride): Boolean =
        inputMicrosPerMillion == other.inputMicrosPerMillion && outputMicrosPerMillion == other.outputMicrosPerMillion
}
