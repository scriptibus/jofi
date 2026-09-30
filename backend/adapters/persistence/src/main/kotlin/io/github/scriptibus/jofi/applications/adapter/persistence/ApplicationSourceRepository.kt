// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationSourceRecord
import io.github.scriptibus.jofi.shared.adapter.persistence.violatedConstraint
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/**
 * Where applications were found (`application_source`, ADR-0046), in the caller's transaction. A source's first
 * snapshot is written through [DescriptionSnapshotRepository], so both use the same row mapping. Links may carry
 * personal tracking parameters and posting text is untrusted: only operations and exception types are logged.
 */
@Component
class ApplicationSourceRepository(
    private val dsl: DSLContext,
    private val snapshots: DescriptionSnapshotRepository,
) : ApplicationSourceRepositoryPort {
    private val tables = ApplicationTables(dsl)

    /** Locks the application's row (`FOR NO KEY UPDATE`), so concurrent adds count each other. */
    override fun add(
        source: ApplicationSource,
        discovery: DescriptionSnapshot?,
    ): ApplicationStoreResult<Unit> =
        storeCall("add source") {
            val locked =
                dsl
                    .select(APPLICATION.ID)
                    .from(APPLICATION)
                    .where(APPLICATION.ID.eq(source.application.value))
                    .forNoKeyUpdate()
                    .fetchOne()
            when {
                locked == null -> {
                    ApplicationStoreResult.NotFound
                }

                dsl.fetchCount(APPLICATION_SOURCE, APPLICATION_SOURCE.APPLICATION_ID.eq(source.application.value)) >=
                    Application.MAX_SOURCES -> {
                    ApplicationStoreResult.SourceLimitReached
                }

                else -> {
                    dsl.insertInto(APPLICATION_SOURCE).set(toRecord(source)).execute()
                    discovery?.let(snapshots::add) ?: ApplicationStoreResult.Success(Unit)
                }
            }
        }

    override fun findById(
        application: ApplicationId,
        id: SourceId,
    ): ApplicationStoreResult<ApplicationSource> =
        storeCall("find source") {
            tables
                .sourcesOf(application)
                .firstOrNull { it.id == id }
                ?.let { ApplicationStoreResult.Success(it) } ?: ApplicationStoreResult.NotFound
        }

    override fun updateAvailability(source: ApplicationSource): ApplicationStoreResult<Unit> =
        storeCall("update source availability") {
            val updated =
                dsl
                    .update(APPLICATION_SOURCE)
                    .set(APPLICATION_SOURCE.OFFLINE_SINCE, source.offlineSince?.atOffset(ZoneOffset.UTC))
                    .where(APPLICATION_SOURCE.ID.eq(source.id.value))
                    .and(APPLICATION_SOURCE.APPLICATION_ID.eq(source.application.value))
                    .execute()
            if (updated == 0) ApplicationStoreResult.NotFound else ApplicationStoreResult.Success(Unit)
        }

    override fun findByOriginalUrl(url: WebAddress): ApplicationStoreResult<List<ApplicationSource>> =
        storeCall("find sources by link") {
            val applications =
                dsl
                    .selectDistinct(APPLICATION_SOURCE.APPLICATION_ID)
                    .from(APPLICATION_SOURCE)
                    .where(APPLICATION_SOURCE.ORIGINAL_URL.eq(url.value))
                    .fetch { ApplicationId(it.value1()) }
            ApplicationStoreResult.Success(
                tables
                    .sourcesOf(applications)
                    .values
                    .flatten()
                    .filter { it.originalUrl == url },
            )
        }

    private fun toRecord(source: ApplicationSource): ApplicationSourceRecord =
        ApplicationSourceRecord().apply {
            id = source.id.value
            applicationId = source.application.value
            kind = source.kind.name
            originalUrl = source.originalUrl?.value
            discoveredAt = source.discoveredAt.atOffset(ZoneOffset.UTC)
            offlineSince = source.offlineSince?.atOffset(ZoneOffset.UTC)
        }

    /** No exception crosses the port; an insert whose application is gone is recognised by the foreign key's name. */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            if (exception.violatedConstraint() == SOURCE_APPLICATION_FK) {
                ApplicationStoreResult.NotFound
            } else {
                log.error("Application source store {} failed: {}", operation, exception.javaClass.name)
                ApplicationStoreResult.StorageFailure(operation)
            }
        }

    private companion object {
        /** `application_source.application_id`: the application is gone. */
        const val SOURCE_APPLICATION_FK = "application_source_application_fk"

        val log: Logger = LoggerFactory.getLogger(ApplicationSourceRepository::class.java)
    }
}
