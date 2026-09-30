// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult

/**
 * Stores the one set of [ApplicationSettings] (table `application_settings`, at most one row; implemented with the
 * use cases in #85). No row means the user never changed them: [find] answers [ApplicationSettings.DEFAULT] then,
 * so a restored backup from before the table keeps the defaults. The use case appends the changelog entry
 * ([ApplicationSettings.ENTITY_REF], with the values, which are not personal) in the same transaction.
 * Implementations never throw.
 */
interface ApplicationSettingsRepositoryPort {
    fun find(): ApplicationStoreResult<ApplicationSettings>

    /**
     * Stores [settings] only if the stored version (0 without a row) is exactly one below its own, inserting the row
     * on the first change; [ApplicationStoreResult.VersionConflict] otherwise.
     */
    fun update(settings: ApplicationSettings): ApplicationStoreResult<Unit>
}
