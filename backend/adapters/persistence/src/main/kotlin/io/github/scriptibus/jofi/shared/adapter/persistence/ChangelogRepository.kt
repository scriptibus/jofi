// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** jOOQ implementation of the append-only audit trail on the `changelog_entry` table. */
@Component
class ChangelogRepository(
    private val dsl: DSLContext,
) : ChangelogPort {
    override fun append(entry: ChangelogEntry): ChangelogResult<Unit> =
        guarded("append") {
            dsl.executeInsert(ChangelogRecordMapper.toRecord(entry))
        }

    override fun listByEntity(
        entity: EntityRef,
        limit: ChangelogLimit,
    ): ChangelogResult<List<ChangelogEntry>> =
        guarded("listByEntity") {
            dsl
                .selectFrom(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(entity.type))
                .and(CHANGELOG_ENTRY.ENTITY_ID.eq(entity.id))
                .orderBy(CHANGELOG_ENTRY.OCCURRED_AT.desc(), CHANGELOG_ENTRY.ID.desc())
                .limit(limit.value)
                .fetch()
                .map(ChangelogRecordMapper::toDomain)
                .asReversed()
        }

    override fun listRecent(limit: ChangelogLimit): ChangelogResult<List<ChangelogEntry>> =
        guarded("listRecent") {
            dsl
                .selectFrom(CHANGELOG_ENTRY)
                .orderBy(CHANGELOG_ENTRY.OCCURRED_AT.desc(), CHANGELOG_ENTRY.ID.desc())
                .limit(limit.value)
                .fetch()
                .map(ChangelogRecordMapper::toDomain)
        }

    // No exception crosses the port (AGENTS.md §3). Only the operation and the exception type are
    // logged: exception messages can carry row values (personal data), which must not reach logs.
    private fun <T> guarded(
        operation: String,
        block: () -> T,
    ): ChangelogResult<T> =
        try {
            ChangelogResult.Success(block())
        } catch (exception: RuntimeException) {
            logger.error("Changelog {} failed: {}", operation, exception.javaClass.name)
            ChangelogResult.StorageFailure(operation)
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ChangelogRepository::class.java)
    }
}
