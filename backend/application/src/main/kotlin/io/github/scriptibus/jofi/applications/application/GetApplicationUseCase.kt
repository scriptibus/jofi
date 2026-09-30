// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetApplicationPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult

/** One application with its contact links. Reading it does not mark it read (`SetApplicationUnreadUseCase`). */
class GetApplicationUseCase(
    private val applications: ApplicationRepositoryPort,
) : GetApplicationPort {
    override fun execute(id: ApplicationId): ApplicationResult<Application> = applications.findById(id).toResult()
}
