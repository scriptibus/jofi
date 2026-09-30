// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.persistence

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.CurrencyCode
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.DeclineReason
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndTone
import io.github.scriptibus.jofi.applications.domain.LanguageTag
import io.github.scriptibus.jofi.applications.domain.OfferDetails
import io.github.scriptibus.jofi.applications.domain.Pay
import io.github.scriptibus.jofi.applications.domain.PayBand
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySource
import io.github.scriptibus.jofi.applications.domain.RemoteShare
import io.github.scriptibus.jofi.applications.domain.Score
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.StatusChangeRequest
import io.github.scriptibus.jofi.applications.domain.StatusTransition
import io.github.scriptibus.jofi.applications.domain.Tone
import io.github.scriptibus.jofi.setup.adapter.persistence.ConfirmedProofs
import io.github.scriptibus.jofi.shared.adapter.persistence.PostgresTestDatabase
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION_STATUS_CHANGE
import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * `ApplicationRepository` on a real PostgreSQL migrated from zero: round trips, and that each write keeps
 * to its own columns (ADR-0041), stores only on top of its version and maps foreign keys by name.
 */
class ApplicationRepositoryTest {
    private lateinit var dsl: DSLContext
    private lateinit var repository: ApplicationRepository
    private lateinit var rows: ApplicationRows
    private var company = CompanyRef(UUID(0, 0))

    @BeforeEach
    fun migrateFromZero() {
        dsl = PostgresTestDatabase.migratedFromZero()
        repository = ApplicationRepository(dsl)
        rows = ApplicationRows(dsl)
        company = CompanyRef(rows.company())
    }

    private fun stored(title: String = "Backend Engineer"): Application {
        val application =
            Application.create(
                ApplicationId(UUID.randomUUID()),
                ApplicationDetails(title, company),
                CREATED,
            )
        repository.add(application, StatusChange.initial(application, Actor.User)) shouldBe
            ApplicationStoreResult.Success(Unit)
        return application
    }

    private fun read(id: ApplicationId): Application =
        repository.findById(id).shouldBeInstanceOf<ApplicationStoreResult.Success<Application>>().value

    private fun history(id: ApplicationId): List<StatusChange> =
        repository.statusHistory(id).shouldBeInstanceOf<ApplicationStoreResult.Success<List<StatusChange>>>().value

    private fun contact(): ContactRef = ContactRef(rows.contact(company.value))

    @Test
    fun `an application with every field reads back equal, with its first history entry`() {
        val application =
            Application(
                ApplicationId(UUID.randomUUID()),
                everyDetail(),
                setOf(contact(), contact()),
                unread = true,
                wantScore = Score(45),
                fitScore = Score(0),
                status = ApplicationStatus.DECLINED,
                declineReason = DeclineReason(DeclineCategory.SALARY, "Too little, \"sadly\""),
                version = 3,
                createdAt = CREATED,
                updatedAt = LATER,
            )
        val initial = StatusChange.initial(application, Actor.Scanner("arbeitsagentur"))

        repository.add(application, initial) shouldBe ApplicationStoreResult.Success(Unit)

        read(application.id) shouldBe application
        history(application.id) shouldContainExactly listOf(initial)
        repository.findById(ApplicationId(UUID.randomUUID())) shouldBe ApplicationStoreResult.NotFound
        repository.statusHistory(ApplicationId(UUID.randomUUID())) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `a pay band from the posting and an offer without salary read back equal`() {
        val details =
            ApplicationDetails(
                "Backend Engineer",
                company,
                payBand = PayBand(null, BigDecimal("40.50"), CurrencyCode("CHF"), PayPeriod.HOUR, PaySource.Posting),
                offer = OfferDetails(answerBy = LocalDate.parse("2026-11-01")),
            )
        val application = Application.create(ApplicationId(UUID.randomUUID()), details, CREATED)

        repository.add(application, StatusChange.initial(application, Actor.User)) shouldBe
            ApplicationStoreResult.Success(Unit)

        read(application.id) shouldBe application
    }

    @Test
    fun `an unknown company is refused by the name of its foreign key, on add and on update`() {
        val unknown = CompanyRef(UUID.randomUUID())
        val orphan = Application.create(ApplicationId(UUID.randomUUID()), ApplicationDetails("X", unknown), CREATED)

        repository.add(orphan, StatusChange.initial(orphan, Actor.User)) shouldBe ApplicationStoreResult.CompanyNotFound

        val application = stored()
        repository.updateDetails(application.edit(ApplicationDetails("X", unknown), LATER)) shouldBe
            ApplicationStoreResult.CompanyNotFound
        read(application.id) shouldBe application
    }

    @Test
    fun `a detail edit writes only the details and leaves the flag, scores, status and links`() {
        val application = stored()
        val linked = contact()
        rows.link(application.id.value, linked.value)
        dsl
            .update(APPLICATION)
            .set(APPLICATION.UNREAD, true)
            .set(APPLICATION.WANT_SCORE, BigDecimal("4.5"))
            .set(APPLICATION.STATUS, "APPLIED")
            .where(APPLICATION.ID.eq(application.id.value))
            .execute()
        val edited = application.edit(everyDetail(), LATER)

        repository.updateDetails(edited) shouldBe ApplicationStoreResult.Success(Unit)

        read(application.id) shouldBe
            edited.copy(
                contacts = setOf(linked),
                unread = true,
                wantScore = Score(45),
                status = ApplicationStatus.APPLIED,
            )
    }

    @Test
    fun `a versioned write stores only on top of the version it was based on`() {
        val application = stored()
        val edited = application.edit(ApplicationDetails("Staff Engineer", company), LATER)

        repository.updateDetails(edited) shouldBe ApplicationStoreResult.Success(Unit)
        repository.updateDetails(application.edit(ApplicationDetails("Lead", company), LATER)) shouldBe
            ApplicationStoreResult.VersionConflict
        repository.replaceContacts(application.linkContacts(setOf(contact()), LATER)) shouldBe
            ApplicationStoreResult.VersionConflict

        read(application.id) shouldBe edited
        val unknown = Application.create(ApplicationId(UUID.randomUUID()), ApplicationDetails("X", company), CREATED)
        repository.updateDetails(unknown.edit(ApplicationDetails("Y", company), LATER)) shouldBe
            ApplicationStoreResult.NotFound
    }

    @Test
    fun `replacing contacts rewrites the links only when the set differs`() {
        val application = stored()
        val first = contact()
        val linked = application.linkContacts(setOf(first), LATER)
        repository.replaceContacts(linked) shouldBe ApplicationStoreResult.Success(Unit)
        val rowsBefore = linkRows()

        val same = linked.copy(version = linked.version + 1)
        repository.replaceContacts(same) shouldBe ApplicationStoreResult.Success(Unit)
        linkRows() shouldBe rowsBefore

        val second = contact()
        val relinked = same.linkContacts(setOf(first, second), LATER)
        repository.replaceContacts(relinked) shouldBe ApplicationStoreResult.Success(Unit)
        read(application.id) shouldBe relinked
        linkRows() shouldNotBe rowsBefore
    }

    @Test
    fun `an unknown contact is refused by the name of its foreign key`() {
        val application = stored()

        repository.replaceContacts(application.linkContacts(setOf(ContactRef(UUID.randomUUID())), LATER)) shouldBe
            ApplicationStoreResult.ContactNotFound
    }

    @Test
    fun `a status change writes status and reason, appends its history entry and leaves the details`() {
        val application = stored()
        dsl
            .update(APPLICATION)
            .set(APPLICATION.UNREAD, true)
            .where(APPLICATION.ID.eq(application.id.value))
            .execute()
        val request =
            StatusChangeRequest(ApplicationStatus.DECLINED, "Too far away", DeclineCategory.LOCATION)
        val move =
            application
                .changeStatus(request, Actor.ExternalClient("claude-desktop"), LATER)
                .shouldBeInstanceOf<StatusTransition.Changed>()

        repository.changeStatus(move.application, move.change) shouldBe ApplicationStoreResult.Success(Unit)
        repository.changeStatus(move.application, move.change) shouldBe ApplicationStoreResult.VersionConflict

        read(application.id) shouldBe move.application.copy(unread = true)
        history(application.id) shouldContainExactly listOf(StatusChange.initial(application, Actor.User), move.change)
    }

    @Test
    fun `marking read changes only the flag, never the version`() {
        val application = stored()

        repository.setUnread(application.id, true) shouldBe ApplicationStoreResult.Success(Unit)

        read(application.id) shouldBe application.markUnread(true)
        repository.setUnread(ApplicationId(UUID.randomUUID()), true) shouldBe ApplicationStoreResult.NotFound
    }

    @Test
    fun `a confirmed delete removes the application with its links and history`() {
        val application = stored()
        rows.link(application.id.value, contact().value)
        val other = stored("Frontend Engineer")

        repository.delete(application.id, proofFor(application.id)) shouldBe ApplicationStoreResult.Success(Unit)

        repository.findById(application.id) shouldBe ApplicationStoreResult.NotFound
        dsl.fetchCount(APPLICATION_CONTACT) shouldBe 0
        dsl.fetchCount(APPLICATION_STATUS_CHANGE) shouldBe 1
        repository.delete(application.id, proofFor(application.id)) shouldBe ApplicationStoreResult.NotFound
        read(other.id) shouldBe other
    }

    @Test
    fun `a delete needs a proof for exactly this application`() {
        val application = stored()
        val other = stored()

        repository.delete(application.id, proofFor(other.id)) shouldBe ApplicationStoreResult.NotConfirmed
        repository.delete(
            application.id,
            ConfirmedProofs.of("companies.delete", application.id.value.toString()),
        ) shouldBe
            ApplicationStoreResult.NotConfirmed
        dsl.fetchCount(APPLICATION) shouldBe 2
    }

    @Test
    fun `a failing statement is a storage failure, not an exception`() {
        val application = stored()

        repository.add(application, StatusChange.initial(application, Actor.User)) shouldBe
            ApplicationStoreResult.StorageFailure("add")
    }

    /** The physical rows of the links: a rewrite gives them new ones even for the same values. */
    private fun linkRows(): List<String> =
        dsl.fetchValues("select ctid::text from application_contact order by ctid").map(Any?::toString)

    private fun everyDetail(): ApplicationDetails =
        ApplicationDetails(
            title = "Entwickler:in Zürich",
            company = company,
            location = "İstanbul, \"Remote\"",
            remoteShare = RemoteShare(60),
            employmentType = EmploymentType.WORKING_STUDENT,
            seniority = Seniority.PRINCIPAL,
            deadline = LocalDate.parse("2026-10-31"),
            howApplied = HowApplied.REFERRAL,
            portalNotes = "# Notes\nAccount: me@example.org",
            payBand =
                PayBand(
                    BigDecimal("70000.00"),
                    BigDecimal("9999999999.99"),
                    CurrencyCode("EUR"),
                    PayPeriod.YEAR,
                    PaySource.Estimated("levels.fyi, Berlin", EstimateConfidence.MEDIUM),
                ),
            languageAndTone =
                LanguageAndTone(LanguageTag("de-CH"), LanguageTag("en"), FormOfAddress.DU, Tone.PERSONAL),
            offer =
                OfferDetails(
                    Pay(BigDecimal("80000.00"), CurrencyCode("EUR"), PayPeriod.MONTH),
                    "10 % bonus",
                    "Bike",
                    RemoteShare(100),
                    30,
                    "3 months",
                    LocalDate.parse("2027-01-01"),
                    LocalDate.parse("2026-11-15"),
                ),
        )

    private fun proofFor(id: ApplicationId) = ConfirmedProofs.of(Application.DELETE_OPERATION, id.value.toString())

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-30T08:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-09-30T09:00:00.654321Z")
    }
}
