// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_SOURCE
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** `ApplicationSourceRepository` on a real PostgreSQL migrated from zero (ADR-0046, #96). */
class ApplicationSourceRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ApplicationSourceRepository
    private lateinit var snapshots: DescriptionSnapshotRepository
    private var application = ApplicationId(UUID(0, 0))
    private var other = ApplicationId(UUID(0, 0))

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        snapshots = DescriptionSnapshotRepository(dsl)
        repository = ApplicationSourceRepository(dsl, snapshots)
        val rows = ApplicationRows(dsl)
        val company = rows.company()
        application = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
        other = ApplicationId(UUID.randomUUID()).also { rows.application(it.value, company) }
    }

    private fun source(
        of: ApplicationId = application,
        url: String? = null,
    ) = ApplicationSource(
        SourceId(UUID.randomUUID()),
        of,
        if (url ==
            null
        ) {
            SourceKind.SCANNER
        } else {
            SourceKind.URL
        },
        url?.let(::WebAddress),
        AT,
    )

    @Test
    fun `a source is stored with its discovery snapshot and read back as the application's`() {
        val source = source(url = "https://jobs.example/1?ref=me")
        val discovery =
            DescriptionSnapshot(
                SnapshotId(UUID.randomUUID()),
                source.id,
                DescriptionText("Kotlin"),
                SnapshotReason.DISCOVERY,
                AT,
            )

        repository.add(source, discovery) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findById(application, source.id) shouldBe ApplicationStoreResult.Success(source)
        repository.findById(other, source.id) shouldBe ApplicationStoreResult.NotFound
        snapshots.latest(source.id) shouldBe ApplicationStoreResult.Success(discovery)
    }

    @Test
    fun `a source of an unknown or full application is not stored`() {
        repository.add(source(ApplicationId(UUID.randomUUID())), null) shouldBe ApplicationStoreResult.NotFound
        repeat(Application.MAX_SOURCES) { repository.add(source(), null) shouldBe ApplicationStoreResult.Success(Unit) }

        repository.add(source(), null) shouldBe ApplicationStoreResult.SourceLimitReached
        repository.add(source(other), null) shouldBe ApplicationStoreResult.Success(Unit)

        dsl.fetchCount(APPLICATION_SOURCE) shouldBe Application.MAX_SOURCES + 1
    }

    @Test
    fun `availability changes write only the source's offline time`() {
        val source = source()
        repository.add(source, null)

        repository.updateAvailability(source.markOffline(LATER)) shouldBe ApplicationStoreResult.Success(Unit)
        repository.findById(application, source.id) shouldBe ApplicationStoreResult.Success(source.markOffline(LATER))
        repository.updateAvailability(source.markOffline(LATER).markOnline()) shouldBe
            ApplicationStoreResult.Success(Unit)
        repository.findById(application, source.id) shouldBe ApplicationStoreResult.Success(source)
        repository.updateAvailability(source(other)) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `sources are found by their exact link, across applications`() {
        val link = "https://jobs.example/careers"
        val first = source(url = link)
        val second = source(other, link)
        listOf(
            first,
            second,
            source(url = "https://jobs.example/careers/"),
            source(),
        ).forEach { repository.add(it, null) }

        val found = repository.findByOriginalUrl(WebAddress(link)) as ApplicationStoreResult.Success

        found.value shouldContainExactlyInAnyOrder listOf(first, second)
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        dsl.execute("drop table application_source cascade")

        repository.add(source(), null) shouldBe ApplicationStoreResult.StorageFailure("add source")
        repository.findById(application, SourceId(UUID.randomUUID())) shouldBe
            ApplicationStoreResult.StorageFailure("find source")
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-30T08:00:00Z")
        val LATER: Instant = AT.plusSeconds(3600)
    }
}
