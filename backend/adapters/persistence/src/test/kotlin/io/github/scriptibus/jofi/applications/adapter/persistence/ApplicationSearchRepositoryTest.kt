// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationOrder
import io.github.scriptibus.jofi.applications.domain.ApplicationPage
import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.ScoreRange
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.applications.domain.TimeRange
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.tables.records.ApplicationRecord
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The application list (#83) on a real PostgreSQL migrated from zero: every filter alone and combined, every
 * sort key in both directions, and offset paging that neither repeats nor skips rows when sort keys tie.
 */
class ApplicationSearchRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ApplicationRepository
    private lateinit var rows: ApplicationRows
    private lateinit var acme: UUID

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = ApplicationRepository(dsl)
        rows = ApplicationRows(dsl)
        acme = rows.company()
    }

    private fun application(
        title: String = "Backend Engineer",
        company: UUID = acme,
        customize: ApplicationRecord.() -> Unit = {},
    ): UUID =
        UUID.randomUUID().also { id ->
            rows.application(id, company) {
                this.title = title
                customize()
            }
        }

    private fun company(name: String): UUID =
        rows.company().also {
            dsl
                .update(COMPANY)
                .set(COMPANY.NAME, name)
                .where(COMPANY.ID.eq(it))
                .execute()
        }

    private fun source(
        application: UUID,
        kind: String,
    ) {
        dsl.execute(
            "insert into application_source (id, application_id, kind, original_url, discovered_at) " +
                "values (?, ?, ?, 'https://jobs.example/1', now())",
            UUID.randomUUID(),
            application,
            kind,
        )
    }

    private fun page(search: ApplicationSearch): ApplicationPage<Application> =
        repository
            .search(
                search,
            ).shouldBeInstanceOf<ApplicationStoreResult.Success<ApplicationPage<Application>>>()
            .value

    private fun ids(search: ApplicationSearch): List<UUID> = page(search).items.map { it.id.value }

    private fun at(hour: Int): OffsetDateTime = OffsetDateTime.of(2026, 9, 30, hour, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `without filters every application is listed, most recently updated first, with the total`() {
        val old = application { updatedAt = at(9) }
        val newest = application { updatedAt = at(11) }
        val middle = application { updatedAt = at(10) }

        val page = page(ApplicationSearch())

        page.items.map { it.id.value } shouldContainExactly listOf(newest, middle, old)
        page.total shouldBe 3
    }

    @Test
    fun `the page carries every application's contact links and sources`() {
        val contact = rows.contact(acme)
        val linked = application { updatedAt = at(10) }
        val plain = application { updatedAt = at(9) }
        rows.link(linked, contact)
        source(linked, "URL")
        source(linked, "SCANNER")

        val (first, second) = page(ApplicationSearch()).items

        first.contacts shouldBe setOf(ContactRef(contact))
        first.sources.map { it.kind } shouldContainExactlyInAnyOrder listOf(SourceKind.URL, SourceKind.SCANNER)
        second.id.value shouldBe plain
        second.contacts shouldBe emptySet()
        second.sources shouldBe emptyList()
    }

    @Test
    fun `the text matches titles fuzzily, best match first, and treats LIKE wildcards literally`() {
        val exact = application("Backend Engineer") { updatedAt = at(8) }
        val longer = application("Senior Backend Engineer (Kotlin)") { updatedAt = at(9) }
        application("Product Designer") { updatedAt = at(10) }
        val percent = application("Sales, 100% remote")
        application("Sales, remote")

        ids(ApplicationSearch(text = "backend engineer")) shouldContainExactly listOf(exact, longer)
        ids(ApplicationSearch(text = "bakend engineer")) shouldContainExactly listOf(exact, longer)
        ids(ApplicationSearch(text = "%")) shouldContainExactly listOf(percent)
    }

    @Test
    fun `company and linked contact filter`() {
        val globex = company("Globex")
        val contact = rows.contact(acme)
        val ofAcme = application()
        val ofGlobex = application(company = globex)
        rows.link(ofGlobex, contact)

        ids(ApplicationSearch(company = CompanyRef(acme))) shouldContainExactly listOf(ofAcme)
        ids(ApplicationSearch(company = CompanyRef(globex))) shouldContainExactly listOf(ofGlobex)
        ids(ApplicationSearch(contact = ContactRef(contact))) shouldContainExactly listOf(ofGlobex)
        ids(ApplicationSearch(contact = ContactRef(UUID.randomUUID()))) shouldBe emptyList()
    }

    @Test
    fun `statuses match any of them, unread filters both ways`() {
        val applied = application { status = "APPLIED" }
        val offer = application { status = "OFFER" }
        val discovered = application { unread = true }

        ids(
            ApplicationSearch(statuses = setOf(ApplicationStatus.APPLIED, ApplicationStatus.OFFER)),
        ) shouldContainExactlyInAnyOrder
            listOf(applied, offer)
        ids(ApplicationSearch(statuses = setOf(ApplicationStatus.DISCOVERED))) shouldContainExactly listOf(discovered)
        ids(ApplicationSearch(unread = true)) shouldContainExactly listOf(discovered)
        ids(ApplicationSearch(unread = false)) shouldContainExactlyInAnyOrder listOf(applied, offer)
    }

    @Test
    fun `languages match the effective application language by prefix, ignoring case`() {
        val german = application { applicationLanguage = "de" }
        val swiss = application { postingLanguage = "de-CH" }
        val shouted = application { applicationLanguage = "DE-at" }
        val englishOverGerman =
            application {
                postingLanguage = "de"
                applicationLanguage = "en"
            }
        application { applicationLanguage = "deu" }
        application()

        ids(ApplicationSearch(languages = setOf(LanguageTag("de")))) shouldContainExactlyInAnyOrder
            listOf(german, swiss, shouted)
        ids(ApplicationSearch(languages = setOf(LanguageTag("de-CH")))) shouldContainExactly listOf(swiss)
        ids(
            ApplicationSearch(languages = setOf(LanguageTag("en"), LanguageTag("de-AT"))),
        ) shouldContainExactlyInAnyOrder
            listOf(englishOverGerman, shouted)
    }

    @Test
    fun `source kinds match applications with any source of that kind, each once`() {
        val scanned = application()
        source(scanned, "SCANNER")
        source(scanned, "SCANNER")
        source(scanned, "URL")
        val imported = application()
        source(imported, "URL")
        application()

        ids(ApplicationSearch(sourceKinds = setOf(SourceKind.SCANNER))) shouldContainExactly listOf(scanned)
        page(ApplicationSearch(sourceKinds = setOf(SourceKind.URL))).total shouldBe 2
        ids(ApplicationSearch(sourceKinds = setOf(SourceKind.MANUAL_CHAT))) shouldBe emptyList()
    }

    @Test
    fun `date ranges include their start and exclude their end`() {
        val nine =
            application {
                createdAt = at(9)
                updatedAt = at(12)
            }
        val ten =
            application {
                createdAt = at(10)
                updatedAt = at(10)
            }

        ids(ApplicationSearch(created = TimeRange(at(9).toInstant(), at(10).toInstant()))) shouldContainExactly
            listOf(nine)
        ids(ApplicationSearch(created = TimeRange(at(10).toInstant(), null))) shouldContainExactly listOf(ten)
        ids(ApplicationSearch(updated = TimeRange(null, at(12).toInstant()))) shouldContainExactly listOf(ten)
        ids(ApplicationSearch(updated = TimeRange(at(11).toInstant(), null))) shouldContainExactly listOf(nine)
    }

    @Test
    fun `score ranges are inclusive and never match a missing score`() {
        val high =
            application {
                wantScore = BigDecimal("4.5")
                fitScore = BigDecimal("2.0")
            }
        val low = application { wantScore = BigDecimal("1.0") }
        application()

        ids(ApplicationSearch(wantScore = ScoreRange(Score(45), null))) shouldContainExactly listOf(high)
        ids(ApplicationSearch(wantScore = ScoreRange(null, Score(10)))) shouldContainExactly listOf(low)
        ids(ApplicationSearch(wantScore = ScoreRange(Score(0), Score(50)))) shouldContainExactlyInAnyOrder
            listOf(high, low)
        ids(ApplicationSearch(fitScore = ScoreRange(Score(0), null))) shouldContainExactly listOf(high)
    }

    @Test
    fun `filters combine, and missing any one of them leaves an application out of the page and the total`() {
        val contact = rows.contact(acme)

        fun candidate(
            title: String = "Kotlin Backend Engineer",
            status: String = "APPLIED",
            language: String = "de",
            linked: Boolean = true,
        ): UUID =
            application(title) {
                this.status = status
                applicationLanguage = language
                unread = true
            }.also {
                if (linked) rows.link(it, contact)
                source(it, "URL")
            }
        val match = candidate()
        candidate(title = "Product Designer")
        candidate(status = "OFFER")
        candidate(language = "en")
        candidate(linked = false)

        val page = page(everyFilter(ContactRef(contact)))

        page.items.map { it.id.value } shouldContainExactly listOf(match)
        page.total shouldBe 1
        page(ApplicationSearch(statuses = setOf(ApplicationStatus.APPLIED), size = 1)).total shouldBe 4
    }

    private fun everyFilter(contact: ContactRef) =
        ApplicationSearch(
            text = "backend",
            company = CompanyRef(acme),
            contact = contact,
            statuses = setOf(ApplicationStatus.APPLIED),
            unread = true,
            languages = setOf(LanguageTag("de")),
            sourceKinds = setOf(SourceKind.URL),
            created = TimeRange(ApplicationRows.NOW.toInstant(), null),
        )

    @Test
    fun `dates and titles sort both ways`() {
        val alpha =
            application("Alpha") {
                createdAt = at(8)
                updatedAt = at(11)
            }
        val beta =
            application("beta") {
                createdAt = at(9)
                updatedAt = at(9)
            }

        ids(sorted(ApplicationSortKey.UPDATED, SortDirection.ASCENDING)) shouldContainExactly listOf(beta, alpha)
        ids(sorted(ApplicationSortKey.UPDATED, SortDirection.DESCENDING)) shouldContainExactly listOf(alpha, beta)
        ids(sorted(ApplicationSortKey.CREATED, SortDirection.ASCENDING)) shouldContainExactly listOf(alpha, beta)
        ids(sorted(ApplicationSortKey.CREATED, SortDirection.DESCENDING)) shouldContainExactly listOf(beta, alpha)
        ids(sorted(ApplicationSortKey.TITLE, SortDirection.ASCENDING)) shouldContainExactly listOf(alpha, beta)
        ids(sorted(ApplicationSortKey.TITLE, SortDirection.DESCENDING)) shouldContainExactly listOf(beta, alpha)
    }

    @Test
    fun `company names sort both ways, and an explicit order wins over the best match`() {
        val zeta = application("Backend Engineer", company("Zeta"))
        val acmes = application("Backend Engineer, Senior")

        ids(sorted(ApplicationSortKey.COMPANY, SortDirection.ASCENDING)) shouldContainExactly listOf(acmes, zeta)
        ids(sorted(ApplicationSortKey.COMPANY, SortDirection.DESCENDING)) shouldContainExactly listOf(zeta, acmes)
        ids(
            sorted(ApplicationSortKey.COMPANY, SortDirection.DESCENDING).copy(text = "backend engineer"),
        ) shouldContainExactly
            listOf(zeta, acmes)
        page(
            sorted(ApplicationSortKey.COMPANY, SortDirection.ASCENDING).copy(company = CompanyRef(acme)),
        ).total shouldBe
            1
    }

    @Test
    fun `statuses sort in pipeline order, not alphabetically`() {
        val offer = application { status = "OFFER" }
        val discovered = application()
        val ghosted = application { status = "GHOSTED" }
        val applied = application { status = "APPLIED" }

        ids(sorted(ApplicationSortKey.STATUS, SortDirection.ASCENDING)) shouldContainExactly
            listOf(discovered, applied, offer, ghosted)
        ids(sorted(ApplicationSortKey.STATUS, SortDirection.DESCENDING)) shouldContainExactly
            listOf(ghosted, offer, applied, discovered)
    }

    @Test
    fun `deadlines sort both ways with the ones without a deadline last`() {
        val none = application()
        val soon = application { deadline = LocalDate.parse("2026-10-01") }
        val later = application { deadline = LocalDate.parse("2026-11-01") }

        ids(sorted(ApplicationSortKey.DEADLINE, SortDirection.ASCENDING)) shouldContainExactly listOf(soon, later, none)
        ids(sorted(ApplicationSortKey.DEADLINE, SortDirection.DESCENDING)) shouldContainExactly
            listOf(later, soon, none)
    }

    @Test
    fun `pages neither repeat nor skip applications when every sort key ties`() {
        val all = (1..7).map { application() }

        for (key in ApplicationSortKey.entries) {
            val pages = (0..3).map { page(sorted(key, key.defaultDirection).copy(page = it, size = 2)) }

            pages.flatMap { page -> page.items.map { it.id.value } } shouldContainExactlyInAnyOrder all
            pages.map { it.total }.toSet() shouldBe setOf(7L)
        }
        ids(ApplicationSearch(page = 4, size = 2)) shouldBe emptyList()
    }

    @Test
    fun `a text search pages stably too`() {
        val all = (1..5).map { application("Backend Engineer") }

        val pages = (0..2).flatMap { ids(ApplicationSearch(text = "backend", page = it, size = 2)) }

        pages shouldContainExactlyInAnyOrder all
    }

    private fun sorted(
        key: ApplicationSortKey,
        direction: SortDirection,
    ) = ApplicationSearch(order = ApplicationOrder(key, direction))
}
