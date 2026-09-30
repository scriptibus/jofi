// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetApplicationSettingsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings

/** The application settings (ADR-0050); the defaults until the user changes them. Reads only. */
class GetApplicationSettingsUseCase(
    private val settings: ApplicationSettingsRepositoryPort,
) : GetApplicationSettingsPort {
    override fun execute(): ApplicationResult<ApplicationSettings> = settings.find().toResult()
}
