// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.ImportStatus
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.PostingImportRecord
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/**
 * Posting imports (`posting_import`, #96), in the caller's transaction. The pasted text is untrusted and personal:
 * only operations and exception types are logged, never rows.
 */
@Component
class PostingImportRepository(
    private val dsl: DSLContext,
) : PostingImportRepositoryPort {
    override fun add(postingImport: PostingImport): ApplicationStoreResult<Unit> =
        storeCall("add import") {
            dsl.insertInto(POSTING_IMPORT).set(toRecord(postingImport)).execute()
            ApplicationStoreResult.Success(Unit)
        }

    override fun findById(id: ImportId): ApplicationStoreResult<PostingImport> =
        storeCall("find import") {
            dsl
                .fetchOne(
                    POSTING_IMPORT,
                    POSTING_IMPORT.ID.eq(id.value),
                )?.let { ApplicationStoreResult.Success(toDomain(it)) }
                ?: ApplicationStoreResult.NotFound
        }

    override fun update(
        current: PostingImport,
        next: PostingImport,
    ): ApplicationStoreResult<Unit> =
        storeCall("update import") {
            // Everything a step changes; the id and the start time never change.
            val updated =
                dsl
                    .update(POSTING_IMPORT)
                    .set(POSTING_IMPORT.DESCRIPTION, next.text?.value)
                    .set(POSTING_IMPORT.STATUS, next.status.name)
                    .set(POSTING_IMPORT.FAILURE, next.failure?.name)
                    .set(POSTING_IMPORT.APPLICATION_ID, next.application?.value)
                    .set(POSTING_IMPORT.ATTEMPT, next.attempt)
                    .set(POSTING_IMPORT.UPDATED_AT, next.updatedAt.atOffset(ZoneOffset.UTC))
                    .where(POSTING_IMPORT.ID.eq(current.id.value))
                    .and(POSTING_IMPORT.STATUS.eq(current.status.name))
                    .and(POSTING_IMPORT.ATTEMPT.eq(current.attempt))
                    .execute()
            when {
                updated > 0 -> ApplicationStoreResult.Success(Unit)

                dsl.fetchExists(
                    POSTING_IMPORT,
                    POSTING_IMPORT.ID.eq(current.id.value),
                ) -> ApplicationStoreResult.VersionConflict

                else -> ApplicationStoreResult.NotFound
            }
        }

    override fun findPendingByText(text: DescriptionText): ApplicationStoreResult<PostingImport?> =
        storeCall("find pending import by text") {
            ApplicationStoreResult.Success(
                dsl
                    .selectFrom(POSTING_IMPORT)
                    .where(POSTING_IMPORT.STATUS.eq(ImportStatus.PENDING.name))
                    .and(POSTING_IMPORT.DESCRIPTION.eq(text.value))
                    .orderBy(POSTING_IMPORT.CREATED_AT.desc())
                    .limit(1)
                    .fetchOne(::toDomain),
            )
        }

    override fun findPendingBySourceUrl(sourceUrl: WebAddress): ApplicationStoreResult<PostingImport?> =
        storeCall("find pending import by link") {
            ApplicationStoreResult.Success(
                dsl
                    .selectFrom(POSTING_IMPORT)
                    .where(POSTING_IMPORT.STATUS.eq(ImportStatus.PENDING.name))
                    .and(POSTING_IMPORT.SOURCE_URL.eq(sourceUrl.value))
                    .orderBy(POSTING_IMPORT.CREATED_AT.desc())
                    .limit(1)
                    .fetchOne(::toDomain),
            )
        }

    override fun lockForStart(key: String): ApplicationStoreResult<Unit> {
        // Outside a transaction (autocommit) the lock would be released at once and guard nothing: a bug, not a result.
        check(dsl.connectionResult { !it.autoCommit }) { "lockForStart needs the caller's transaction" }
        return storeCall("lock import start") {
            // Held until the caller's transaction ends; the key is hashed, so its length does not matter.
            dsl.fetch("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked", key)
            ApplicationStoreResult.Success(Unit)
        }
    }

    private fun toRecord(postingImport: PostingImport): PostingImportRecord =
        PostingImportRecord().apply {
            id = postingImport.id.value
            description = postingImport.text?.value
            status = postingImport.status.name
            failure = postingImport.failure?.name
            applicationId = postingImport.application?.value
            attempt = postingImport.attempt
            createdAt = postingImport.createdAt.atOffset(ZoneOffset.UTC)
            updatedAt = postingImport.updatedAt.atOffset(ZoneOffset.UTC)
            sourceUrl = postingImport.sourceUrl?.value
        }

    private fun toDomain(record: PostingImportRecord): PostingImport =
        PostingImport(
            ImportId(record.id),
            record.description?.let(::DescriptionText),
            ImportStatus.valueOf(record.status),
            record.failure?.let(ImportFailure::valueOf),
            record.applicationId?.let(::ApplicationId),
            record.attempt,
            record.createdAt.toInstant(),
            record.updatedAt.toInstant(),
            record.sourceUrl?.let(::WebAddress),
        )

    /** No exception crosses the port; messages can carry the pasted text, so only the exception type is logged. */
    private fun <T> storeCall(
        operation: String,
        block: () -> ApplicationStoreResult<T>,
    ): ApplicationStoreResult<T> =
        try {
            block()
        } catch (exception: RuntimeException) {
            log.error("Posting import store {} failed: {}", operation, exception.javaClass.name)
            ApplicationStoreResult.StorageFailure(operation)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PostingImportRepository::class.java)
    }
}
