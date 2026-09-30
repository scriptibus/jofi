// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetApplicationStatusHistoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.StatusChange

/** Every status change of an application, oldest first, from its creation on (ADR-0044). */
class GetApplicationStatusHistoryUseCase(
    private val applications: ApplicationRepositoryPort,
) : GetApplicationStatusHistoryPort {
    override fun execute(id: ApplicationId): ApplicationResult<List<StatusChange>> =
        applications.statusHistory(id).toResult()
}
