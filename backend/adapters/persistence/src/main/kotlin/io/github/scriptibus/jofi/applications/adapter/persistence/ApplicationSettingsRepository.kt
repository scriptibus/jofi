// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SETTINGS
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationSettingsRecord
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset.UTC

/**
 * The one set of application settings (`application_settings`, at most one row, ADR-0050), in the caller's
 * transaction. No row reads as [ApplicationSettings.DEFAULT]; the first change inserts the row, and a concurrent
 * first change loses on the primary key (`ON CONFLICT DO NOTHING`) like a stale version.
 */
@Component
class ApplicationSettingsRepository(
    private val dsl: DSLContext,
) : ApplicationSettingsRepositoryPort {
    override fun find(): ApplicationStoreResult<ApplicationSettings> =
        storeCall("find settings") {
            val settings =
                dsl.selectFrom(APPLICATION_SETTINGS).fetchOne()?.let { row ->
                    ApplicationSettings(
                        ApplicationSettings.Values(row.ghostedAfterWeeks, row.followUpAfterDays),
                        row.version,
                        row.updatedAt.toInstant(),
                    )
                }
            ApplicationStoreResult.Success(settings ?: ApplicationSettings.DEFAULT)
        }

    override fun update(settings: ApplicationSettings): ApplicationStoreResult<Unit> =
        storeCall("update settings") {
            val written = if (settings.version == FIRST_CHANGE) insertRow(settings) else updateRow(settings)
            if (written == 1) ApplicationStoreResult.Success(Unit) else ApplicationStoreResult.VersionConflict
        }

    private fun insertRow(settings: ApplicationSettings): Int =
        dsl
            .insertInto(APPLICATION_SETTINGS)
            .set(toRecord(settings))
            .onConflictDoNothing()
            .execute()

    private fun updateRow(settings: ApplicationSettings): Int =
        dsl
            .update(APPLICATION_SETTINGS)
            .set(toRecord(settings))
            .where(APPLICATION_SETTINGS.VERSION.eq(settings.version - 1))
            .execute()

    private fun toRecord(settings: ApplicationSettings): ApplicationSettingsRecord =
        ApplicationSettingsRecord().apply {
            ghostedAfterWeeks = settings.values.ghostedAfterWeeks
            followUpAfterDays = settings.values.followUpAfterDays
            version = settings.version
            updatedAt = requireNotNull(settings.updatedAt) { "Stored settings have a change time" }.atOffset(UTC)
        }

    /** No exception crosses the port; the values are not personal, but only the exception type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            log.error("Application settings store {} failed: {}", operation, exception.javaClass.name)
            ApplicationStoreResult.StorageFailure(operation)
        }

    private companion object {
        const val FIRST_CHANGE = 1L
        val log: Logger = LoggerFactory.getLogger(ApplicationSettingsRepository::class.java)
    }
}
