// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.Actor

/** Known-good: the setup REST API adds a provider as the logged-in user. Test fixture only. */
class SetupWebFixture(
    private val createProvider: CreateProviderUseCase,
) {
    fun addProvider(url: String) =
        createProvider.execute(ProviderKind.OPENAI_COMPATIBLE, ProviderInput("web", url, null), Actor.User)
}
