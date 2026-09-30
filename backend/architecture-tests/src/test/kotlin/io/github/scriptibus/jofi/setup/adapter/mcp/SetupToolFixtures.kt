// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.mcp

import io.github.scriptibus.jofi.setup.application.CreateProviderUseCase
import io.github.scriptibus.jofi.setup.domain.ProviderInput
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.Actor

/** Known-bad: an MCP tool that adds a provider. Test fixture only, never production. */
class SetupToolFixture(
    private val createProvider: CreateProviderUseCase,
) {
    fun addProvider(url: String) =
        createProvider.execute(
            ProviderKind.OPENAI_COMPATIBLE,
            ProviderInput("tool", url, null),
            Actor.ExternalClient("mcp"),
        )
}

/** Known-bad: an MCP tool that claims to be the user. Test fixture only, never production. */
class UserActingToolFixture {
    fun actor(): Actor = Actor.User
}
