// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.application.port.inbound.ListProvidersPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.SetupResult

/** The configured AI providers; each holds its key's secret id, never the key. */
class ListProvidersUseCase(
    private val providers: ProviderConfigPort,
) : ListProvidersPort {
    override fun execute(): SetupResult<List<ProviderConfig>> = providers.findAll().toSetupResult()
}
