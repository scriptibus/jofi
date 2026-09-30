// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.application

import io.github.scriptibus.jofi.setup.application.port.inbound.ListProviderPrivacyInfoPort
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyCatalog
import io.github.scriptibus.jofi.setup.domain.ProviderPrivacyOverview
import java.time.Clock
import java.time.LocalDate

/**
 * The provider privacy info for the setup wizard (spec §3.2). The catalog is read once at startup
 * from the hand-maintained file; only the stale flags depend on today's date (in the clock's zone, UTC).
 */
class ListProviderPrivacyInfoUseCase(
    private val catalog: ProviderPrivacyCatalog,
    private val clock: Clock,
) : ListProviderPrivacyInfoPort {
    override fun execute(): ProviderPrivacyOverview = catalog.overviewOn(LocalDate.now(clock))
}
