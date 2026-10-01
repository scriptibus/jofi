// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelPriceOverride
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.domain.FieldChange

/**
 * The provider [id] if it is an OpenAI-compatible endpoint, the only kind whose models the user prices
 * (ADR-0055): cloud providers have verified list prices and never borrow or override one (ADR-0043).
 */
internal fun openAiCompatibleProvider(
    providers: ProviderConfigPort,
    id: ProviderId,
): SetupResult<ProviderConfig> =
    providers.findById(id).toSetupResult().then { provider ->
        if (provider.kind ==
            ProviderKind.OPENAI_COMPATIBLE
        ) {
            SetupResult.Success(provider)
        } else {
            SetupResult.PriceNotAllowed
        }
    }

/** What changed between two prices of a model, as the stored micros of a US dollar per million tokens. */
internal fun priceChanges(
    before: ModelPriceOverride?,
    after: ModelPriceOverride?,
): List<FieldChange> =
    listOfNotNull(
        changeOf("inputMicrosPerMillion", before?.inputMicrosPerMillion, after?.inputMicrosPerMillion),
        changeOf("outputMicrosPerMillion", before?.outputMicrosPerMillion, after?.outputMicrosPerMillion),
    )
