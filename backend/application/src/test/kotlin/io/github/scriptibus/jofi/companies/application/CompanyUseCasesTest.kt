// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.application

import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.CLOCK
import io.github.scriptibus.jofi.companies.application.CompanyFixtures.Companion.NOW
import io.github.scriptibus.jofi.companies.domain.CompanyField
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyPreferenceChanged
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.CompanyViolation
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.ViolationKind
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class CompanyUseCasesTest {
    private val fixtures = CompanyFixtures()
    private val create = CreateCompanyUseCase(fixtures.companyPort, fixtures.changelog, fixtures.transactions, CLOCK)
    private val update =
        UpdateCompanyUseCase(
            fixtures.companyPort,
            fixtures.applicationPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val get = GetCompanyUseCase(fixtures.companyPort, fixtures.applicationPort)
    private val search = SearchCompaniesUseCase(fixtures.companyPort, fixtures.applicationPort)
    private val setPreference =
        SetCompanyPreferenceUseCase(
            fixtures.companyPort,
            fixtures.applicationPort,
            fixtures.eventPort,
            fixtures.changelog,
            fixtures.transactions,
            CLOCK,
        )
    private val scanner = Actor.Scanner("arbeitsagentur")

    private fun CompanyResult<CompanyView>.view(): CompanyView =
        shouldBeInstanceOf<CompanyResult.Success<CompanyView>>().value

    @Test
    fun `creating stores the normalized company at microsecond precision and records the actor`() {
        val input = CompanyInput(" Café GmbH ", size = CompanySize.SMALL, researchNotes = "Met at a fair")

        val view = create.execute(input, Actor.Ai).view()

        view.applicationCount shouldBe 0
        view.company.details.name shouldBe "Café GmbH"
        view.company.createdAt shouldBe NOW
        fixtures.companies.values.single() shouldBe view.company
        val entry = fixtures.entries.single()
        entry.entity shouldBe view.company.id.toEntityRef()
        entry.actor shouldBe Actor.Ai
        entry.change.description shouldBe "Created company; research notes changed"
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("name", null, "Café GmbH"), FieldChange("size", null, "SMALL"))
    }

    @Test
    fun `invalid input creates nothing and names the fields`() {
        create.execute(CompanyInput(" ", website = "ftp://acme.example"), Actor.User) shouldBe
            CompanyResult.Invalid(
                listOf(
                    CompanyViolation(CompanyField.WEBSITE, ViolationKind.INVALID_URL),
                    CompanyViolation(CompanyField.NAME, ViolationKind.REQUIRED),
                ),
            )
        fixtures.companies.shouldBeEmpty()
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a failing changelog rolls the new company back`() {
        fixtures.failingChangelog = true

        create.execute(CompanyInput("ACME"), Actor.User) shouldBe CompanyResult.StorageFailure("changelog")
        fixtures.companies.shouldBeEmpty()
    }

    @Test
    fun `updating replaces the details, bumps the version and records the changed fields`() {
        val company = fixtures.company(version = 2)
        fixtures.applicationCounts[company.id.value] = 3

        val view = update.execute(company.id, CompanyInput("ACME SE", industry = "Robotics"), 2, scanner).view()

        view.applicationCount shouldBe 3
        view.company.version shouldBe 3
        view.company.updatedAt shouldBe NOW
        view.company.details.industry shouldBe "Robotics"
        val entry = fixtures.entries.single()
        entry.actor shouldBe scanner
        entry.change.description shouldBe "Edited company"
        entry.change.fieldChanges shouldContainExactly
            listOf(FieldChange("name", "ACME GmbH", "ACME SE"), FieldChange("industry", null, "Robotics"))
    }

    @Test
    fun `unchanged details store nothing and write no entry`() {
        val company = fixtures.company(version = 1)

        update.execute(company.id, CompanyInput(" ACME GmbH "), 1, Actor.User).view().company shouldBe company
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a stale version is a conflict, checked before the input and even for a no-op`() {
        val company = fixtures.company(version = 4)

        update.execute(company.id, CompanyInput("ACME GmbH"), 3, Actor.User) shouldBe CompanyResult.VersionConflict
        update.execute(company.id, CompanyInput(""), 3, Actor.User) shouldBe CompanyResult.VersionConflict
        fixtures.companies[company.id] shouldBe company
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `a change stored by someone else between read and write is a conflict and rolls back`() {
        val company = fixtures.company(version = 1)
        fixtures.concurrentVersion = 2

        update.execute(company.id, CompanyInput("ACME SE"), 1, Actor.User) shouldBe CompanyResult.VersionConflict
        fixtures.companies[company.id] shouldBe company
        fixtures.entries.shouldBeEmpty()
    }

    @Test
    fun `unknown companies are not found`() {
        val unknown = CompanyId(UUID.randomUUID())

        get.execute(unknown) shouldBe CompanyResult.NotFound
        update.execute(unknown, CompanyInput("ACME"), 0, Actor.User) shouldBe CompanyResult.NotFound
        setPreference.execute(unknown, PreferenceInput(PreferenceKind.FAVOURITE), 0, Actor.User) shouldBe
            CompanyResult.NotFound
    }

    @Test
    fun `get and search answer the application counts of the applications context`() {
        val acme = fixtures.company("ACME GmbH")
        val globex = fixtures.company("Globex")
        fixtures.applicationCounts[acme.id.value] = 2

        get.execute(acme.id).view().applicationCount shouldBe 2
        val page = search.execute(CompanySearch()).shouldBeInstanceOf<CompanyResult.Success<*>>().value
        page shouldBe
            CompanyPage(listOf(CompanyView(acme, 2), CompanyView(globex, 0)), 2)
    }

    @Test
    fun `without application counts reads are a storage failure`() {
        val acme = fixtures.company()
        fixtures.countsAvailable = false

        get.execute(acme.id) shouldBe CompanyResult.StorageFailure("count applications")
        search.execute(CompanySearch()) shouldBe CompanyResult.StorageFailure("count applications")
        search.execute(CompanySearch(text = "nothing like it")).shouldBeInstanceOf<CompanyResult.Success<*>>()
    }

    @Test
    fun `blacklisting stores the flag with its reason, records the actor and announces it`() {
        val company = fixtures.company()

        val view =
            setPreference
                .execute(company.id, PreferenceInput(PreferenceKind.BLACKLISTED, " Declined twice "), 0, Actor.Ai)
                .view()

        view.company.preference shouldBe CompanyPreference.Blacklisted("Declined twice")
        view.company.version shouldBe 1
        val entry = fixtures.entries.single()
        entry.actor shouldBe Actor.Ai
        entry.change.description shouldBe "Changed company preference; reason changed"
        entry.change.fieldChanges shouldContainExactly listOf(FieldChange("preference", "NONE", "BLACKLISTED"))
        fixtures.events shouldContainExactly
            listOf(
                CompanyPreferenceChanged(
                    company.id,
                    CompanyPreference.None,
                    CompanyPreference.Blacklisted("Declined twice"),
                    Actor.Ai,
                    NOW,
                ),
            )
    }

    @Test
    fun `clearing the flag drops the reason, and the same preference again is a no-op`() {
        val company = fixtures.company()
        setPreference.execute(company.id, PreferenceInput(PreferenceKind.FAVOURITE, "Great team"), 0, Actor.User)

        val cleared = setPreference.execute(company.id, PreferenceInput(PreferenceKind.NONE, "ignored"), 1, Actor.User)
        val again = setPreference.execute(company.id, PreferenceInput(PreferenceKind.NONE), 2, Actor.User)

        cleared.view().company.preference shouldBe CompanyPreference.None
        again.view().company.version shouldBe 2
        fixtures.entries.size shouldBe 2
        fixtures.events.size shouldBe 2
    }

    @Test
    fun `preference changes check the version and the reason`() {
        val company = fixtures.company(version = 1)

        setPreference.execute(company.id, PreferenceInput(PreferenceKind.FAVOURITE), 0, Actor.User) shouldBe
            CompanyResult.VersionConflict
        setPreference.execute(
            company.id,
            PreferenceInput(PreferenceKind.FAVOURITE, "x".repeat(1_001)),
            1,
            Actor.User,
        ) shouldBe
            CompanyResult.Invalid(listOf(CompanyViolation(CompanyField.PREFERENCE_REASON, ViolationKind.TOO_LONG)))
        fixtures.events.shouldBeEmpty()
    }

    @Test
    fun `an event that cannot be published rolls the preference change back`() {
        val company = fixtures.company()
        fixtures.failingEvents = true

        setPreference.execute(company.id, PreferenceInput(PreferenceKind.FAVOURITE), 0, Actor.User) shouldBe
            CompanyResult.StorageFailure("publish event")
        fixtures.companies[company.id] shouldBe company
        fixtures.entries.shouldBeEmpty()
    }
}
