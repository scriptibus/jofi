// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.domain.text.WebAddress

/**
 * Stores where applications were found (table `application_source`, implemented with the use cases in #86
 * and #96). Sources are written only here, never by [ApplicationRepositoryPort]'s writes, and adding one or
 * changing its availability is not a new version of the application. The use case appends the changelog
 * entry (entity [SourceId.ENTITY_TYPE]; the kind, never the link, which may carry personal tracking
 * parameters) in the same transaction. Implementations never throw and never log row data. An insert whose
 * application is gone (`application_source_application_fk`, by name) is [ApplicationStoreResult.NotFound].
 */
interface ApplicationSourceRepositoryPort {
    /**
     * Stores a new source and, if given, its first snapshot ([discovery], of this source), both or neither.
     * The use case checked the limit before ([Application.addSource]); the adapter counts again under a lock of
     * the application's row, so concurrent adds (which take no version) can never store more than
     * [Application.MAX_SOURCES] and make the application unreadable: [ApplicationStoreResult.SourceLimitReached].
     */
    fun add(
        source: ApplicationSource,
        discovery: DescriptionSnapshot?,
    ): ApplicationStoreResult<Unit>

    /** The source [id] of [application]; [ApplicationStoreResult.NotFound] if the application has no such source. */
    fun findById(
        application: ApplicationId,
        id: SourceId,
    ): ApplicationStoreResult<ApplicationSource>

    /** Writes only `offline_since` ([ApplicationSource.markOffline], [ApplicationSource.markOnline]; M4). */
    fun updateAvailability(source: ApplicationSource): ApplicationStoreResult<Unit>

    /**
     * The sources whose original link is exactly [url] (as stored), for the URL import's "already imported"
     * check (#97). Several applications may share a link, e.g. a careers page listing several jobs.
     */
    fun findByOriginalUrl(url: WebAddress): ApplicationStoreResult<List<ApplicationSource>>
}
