// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.GetSavedViewPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewId

/** One saved view, read tolerantly (it may come back adjusted, ADR-0050). Reads only. */
class GetSavedViewUseCase(
    private val views: SavedViewRepositoryPort,
) : GetSavedViewPort {
    override fun execute(id: SavedViewId): ApplicationResult<SavedView> = views.findById(id).savedViewResult()
}
