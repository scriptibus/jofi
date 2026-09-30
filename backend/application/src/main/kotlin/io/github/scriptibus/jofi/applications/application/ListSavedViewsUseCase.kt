// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ListSavedViewsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView

/** Every saved view by name, read tolerantly (ADR-0050). Reads only. */
class ListSavedViewsUseCase(
    private val views: SavedViewRepositoryPort,
) : ListSavedViewsPort {
    override fun execute(): ApplicationResult<List<SavedView>> = views.list().toResult()
}
