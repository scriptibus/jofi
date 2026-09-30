// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.Company
import io.github.scriptibus.jofi.companies.domain.CompanyDetails
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.CompanyPreference
import io.github.scriptibus.jofi.companies.domain.CompanyProfile
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.companies.domain.CompanyView
import io.github.scriptibus.jofi.companies.domain.PreferenceInput
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.companies.domain.WebAddress
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

class CompanyDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val uuid = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    @ParameterizedTest
    @EnumSource(CompanySize::class)
    fun `every company size has an API band`(size: CompanySize) {
        CompanySizeBand.from(size).toDomain() shouldBe size
    }

    @ParameterizedTest
    @EnumSource(PreferenceKind::class)
    fun `every preference kind has an API kind`(kind: PreferenceKind) {
        CompanyPreferenceKind.from(kind).toDomain() shouldBe kind
    }

    @Test
    fun `requests become domain input unchanged, validation is the domain's job`() {
        CompanyDetailsRequest(" ACME ", size = CompanySizeBand.SMALL, locations = listOf(" Berlin ")).toInput() shouldBe
            CompanyInput(" ACME ", size = CompanySize.SMALL, locations = listOf(" Berlin "))
        CompanyDetailsRequest("ACME").toInput() shouldBe CompanyInput("ACME")
        CompanyPreferenceRequest(CompanyPreferenceKind.FAVOURITE, " Great ", basedOnVersion = 2).toInput() shouldBe
            PreferenceInput(PreferenceKind.FAVOURITE, " Great ")
    }

    private val company =
        Company(
            id = CompanyId(uuid),
            details =
                CompanyDetails(
                    name = "ACME GmbH",
                    website = WebAddress("https://acme.example"),
                    industry = "Robotics",
                    size = CompanySize.LARGE,
                    locations = listOf("Berlin", "Remote"),
                    careersPage = WebAddress("https://jobs.example/acme"),
                    researchNotes = "# Notes",
                ),
            profile = CompanyProfile("Builds anvils.", at),
            preference = CompanyPreference.Blacklisted("Declined twice"),
            version = 4,
            createdAt = at,
            updatedAt = at.plusSeconds(60),
        )

    @Test
    fun `a company becomes a response with every field`() {
        val view = CompanyView(company, applicationCount = 2)
        val response = CompanyResponse.from(view)

        response shouldBe
            CompanyResponse(
                id = uuid,
                name = "ACME GmbH",
                website = "https://acme.example",
                industry = "Robotics",
                size = CompanySizeBand.LARGE,
                locations = listOf("Berlin", "Remote"),
                careersPage = "https://jobs.example/acme",
                researchNotes = "# Notes",
                profile = CompanyProfileResponse("Builds anvils.", at),
                preference = CompanyPreferenceKind.BLACKLISTED,
                preferenceReason = "Declined twice",
                version = 4,
                applicationCount = 2,
                createdAt = at,
                updatedAt = at.plusSeconds(60),
            )
        CompanyPageResponse.from(CompanyPage(listOf(view), total = 9), 1, 1) shouldBe
            CompanyPageResponse(listOf(response), page = 1, size = 1, total = 9)
    }

    @Test
    fun `a new company has no optional fields in its response`() {
        val response = CompanyResponse.from(CompanyView(Company.create(CompanyId(uuid), CompanyDetails("ACME"), at), 0))

        response.website shouldBe null
        response.size shouldBe null
        response.profile shouldBe null
        response.preference shouldBe CompanyPreferenceKind.NONE
        response.preferenceReason shouldBe null
        response.locations shouldBe emptyList()
    }
}
