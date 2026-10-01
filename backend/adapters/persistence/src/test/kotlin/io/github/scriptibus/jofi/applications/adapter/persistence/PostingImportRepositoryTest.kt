// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.adapter.persistence.ApplicationRows.Companion.rejects
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.PostingImportRecord
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `PostingImportRepository` and `posting_import` on a real PostgreSQL migrated from zero (#96). */
class PostingImportRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var rows: ApplicationRows
    private lateinit var repository: PostingImportRepository

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        rows = ApplicationRows(dsl)
        repository = PostingImportRepository(dsl)
    }

    private fun started(text: String = "Kotlin Developer at ACME"): PostingImport =
        PostingImport.start(ImportId(UUID.randomUUID()), DescriptionText(text), AT).also {
            repository.add(it) shouldBe ApplicationStoreResult.Success(Unit)
        }

    @Test
    fun `an import round-trips through every step, and a succeeded one keeps no text`() {
        val pending = started()
        repository.findById(pending.id) shouldBe ApplicationStoreResult.Success(pending)

        val failed = pending.failed(ImportFailure.UNREADABLE_ANSWER, AT.plusSeconds(1))
        repository.update(pending, failed) shouldBe ApplicationStoreResult.Success(Unit)
        repository.findById(pending.id) shouldBe ApplicationStoreResult.Success(failed)

        val retried = failed.retried(AT.plusSeconds(2)) ?: error("not retried")
        repository.update(failed, retried) shouldBe ApplicationStoreResult.Success(Unit)
        val done = retried.succeeded(ApplicationId(UUID.randomUUID()), AT.plusSeconds(3))
        repository.update(retried, done) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findById(pending.id) shouldBe ApplicationStoreResult.Success(done)
        dsl.fetchValue(POSTING_IMPORT.DESCRIPTION) shouldBe null
    }

    @Test
    fun `a URL import keeps its link through every step, including success, for the double-submit lookup`() {
        val url = WebAddress("https://jobs.example/42")
        val pending = PostingImport.start(ImportId(UUID.randomUUID()), DescriptionText("Kotlin"), AT, url)
        repository.add(pending) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findPendingBySourceUrl(url) shouldBe ApplicationStoreResult.Success(pending)
        repository.findPendingBySourceUrl(WebAddress("https://jobs.example/99")) shouldBe
            ApplicationStoreResult.Success(null)

        val done = pending.succeeded(ApplicationId(UUID.randomUUID()), AT.plusSeconds(1))
        repository.update(pending, done) shouldBe ApplicationStoreResult.Success(Unit)
        repository.findById(pending.id) shouldBe ApplicationStoreResult.Success(done)
        done.sourceUrl shouldBe url
        // Succeeded, so no longer the answer to a double-submit lookup.
        repository.findPendingBySourceUrl(url) shouldBe ApplicationStoreResult.Success(null)
    }

    @Test
    fun `a pending import with exactly this text answers a double-submit lookup, a different one does not`() {
        val text = DescriptionText("Kotlin Developer at ACME")
        val pending = started(text.value)

        repository.findPendingByText(text) shouldBe ApplicationStoreResult.Success(pending)
        repository.findPendingByText(DescriptionText("Other text")) shouldBe ApplicationStoreResult.Success(null)

        repository.update(pending, pending.failed(ImportFailure.AI_UNAVAILABLE, AT.plusSeconds(1)))
        repository.findPendingByText(text) shouldBe ApplicationStoreResult.Success(null)
    }

    @Test
    fun `a step based on a stale status or attempt changes nothing`() {
        val pending = started()
        val failed = pending.failed(ImportFailure.AI_UNAVAILABLE, AT.plusSeconds(1))
        repository.update(pending, failed)
        val retried = failed.retried(AT.plusSeconds(2)) ?: error("not retried")
        repository.update(failed, retried)

        // A run of the first attempt that finishes late, and a second retry of the same failure.
        repository.update(pending, pending.failed(ImportFailure.NOT_A_POSTING, AT.plusSeconds(3))) shouldBe
            ApplicationStoreResult.VersionConflict
        repository.update(failed, retried) shouldBe ApplicationStoreResult.VersionConflict

        repository.findById(pending.id) shouldBe ApplicationStoreResult.Success(retried)
        val unknown = PostingImport.start(ImportId(UUID.randomUUID()), DescriptionText("x"), AT)
        repository.update(unknown, unknown.failed(ImportFailure.NOT_QUEUED, AT)) shouldBe
            ApplicationStoreResult.NotFound
        repository.findById(unknown.id) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `stores a text at exactly the domain's limit and every failure the domain has`() {
        // The limit in UTF-16 units, which the domain counts (the rocket is two); PostgreSQL counts code points.
        started("ü".repeat(DescriptionText.MAX_LENGTH - 3) + " 🚀")
        ImportFailure.entries.forEach { reason ->
            val pending = started()
            repository.update(pending, pending.failed(reason, AT)) shouldBe ApplicationStoreResult.Success(Unit)
        }

        dsl.fetchCount(POSTING_IMPORT) shouldBe ImportFailure.entries.size + 1
    }

    @Test
    fun `rejects imports the domain rejects, each by its named constraint`() {
        rows.constraintsOf("posting_import") shouldBe CONSTRAINTS
        rejects("posting_import_description_valid") { insert { description = "Text\n" } }
        rejects(
            "posting_import_description_valid",
        ) { insert { description = "x".repeat(DescriptionText.MAX_LENGTH + 1) } }
        rejects("posting_import_status_valid") { insert { status = "pending" } }
        rejects("posting_import_failure_valid") {
            insert {
                status = "FAILED"
                failure = "TIMEOUT"
            }
        }
        rejects("posting_import_failure_matches_status") { insert { failure = "NOT_QUEUED" } }
        rejects("posting_import_application_matches_status") { insert { applicationId = UUID.randomUUID() } }
        rejects("posting_import_description_matches_status") {
            insert {
                status = "SUCCEEDED"
                applicationId = UUID.randomUUID()
            }
        }
        rejects("posting_import_attempt_valid") { insert { attempt = 0 } }
        rejects("posting_import_updated_after_created") { insert { updatedAt = createdAt.minusSeconds(1) } }
        rejects("posting_import_source_url_valid") { insert { sourceUrl = "ftp://jobs.example" } }
        rejects("posting_import_source_url_valid") { insert { sourceUrl = "https://user:pw@jobs.example" } }
        rejects("posting_import_source_url_valid") { insert { sourceUrl = "https://jobs.example/" + "x".repeat(2048) } }
    }

    @Test
    fun `the start lock is taken in a transaction, again by the same one, and for other keys`() {
        dsl.transaction { configuration ->
            val inTransaction = PostingImportRepository(configuration.dsl())
            inTransaction.lockForStart("url:https://jobs.example/1") shouldBe ApplicationStoreResult.Success(Unit)
            inTransaction.lockForStart("url:https://jobs.example/1") shouldBe ApplicationStoreResult.Success(Unit)
            inTransaction.lockForStart("text:" + "x".repeat(100_000)) shouldBe ApplicationStoreResult.Success(Unit)
        }
    }

    @Test
    fun `the start lock outside a transaction fails loudly instead of locking nothing`() {
        val failure = runCatching { repository.lockForStart("url:https://jobs.example/1") }.exceptionOrNull()

        failure.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        val pending = started()
        dsl.execute("drop table posting_import")

        repository.findById(pending.id) shouldBe ApplicationStoreResult.StorageFailure("find import")
        repository.add(pending) shouldBe ApplicationStoreResult.StorageFailure("add import")
        repository.findPendingByText(pending.text ?: error("no text")) shouldBe
            ApplicationStoreResult.StorageFailure("find pending import by text")
        repository.findPendingBySourceUrl(WebAddress("https://jobs.example")) shouldBe
            ApplicationStoreResult.StorageFailure("find pending import by link")
    }

    private fun insert(change: PostingImportRecord.() -> Unit) {
        val record =
            dsl.newRecord(POSTING_IMPORT).apply {
                id = UUID.randomUUID()
                description = "Text"
                status = "PENDING"
                attempt = 1
                createdAt = ApplicationRows.NOW
                updatedAt = ApplicationRows.NOW
                change()
            }
        record.insert()
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T12:00:00.123456Z")
        val CONSTRAINTS =
            listOf(
                "posting_import_application_matches_status",
                "posting_import_attempt_valid",
                "posting_import_description_matches_status",
                "posting_import_description_valid",
                "posting_import_failure_matches_status",
                "posting_import_failure_valid",
                "posting_import_pk",
                "posting_import_source_url_valid",
                "posting_import_status_valid",
                "posting_import_updated_after_created",
            )
    }
}
