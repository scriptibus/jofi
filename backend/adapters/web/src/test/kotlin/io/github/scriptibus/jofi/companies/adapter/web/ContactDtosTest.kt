// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

class ContactDtosTest {
    private val at = Instant.parse("2026-09-30T08:00:00Z")
    private val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val companyUuid = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    @ParameterizedTest
    @EnumSource(ChannelKind::class)
    fun `every channel kind has an API kind`(kind: ChannelKind) {
        ContactChannelKind.from(kind).toDomain() shouldBe kind
    }

    @Test
    fun `requests become domain input unchanged, validation is the domain's job`() {
        val request =
            ContactDetailsRequest(
                name = " Erika ",
                role = "Recruiter",
                companyId = companyUuid,
                channels = listOf(ContactChannelDto(ContactChannelKind.PHONE, " 030 123 ", " work ")),
                relationshipNotes = "Met",
            )

        request.toInput() shouldBe
            ContactInput(
                " Erika ",
                "Recruiter",
                CompanyId(companyUuid),
                listOf(ChannelInput(ChannelKind.PHONE, " 030 123 ", " work ")),
                "Met",
            )
        ContactDetailsRequest("Erika").toInput() shouldBe ContactInput("Erika")
    }

    private val contact =
        Contact(
            ContactId(uuid),
            ContactDetails(
                name = "Erika Mustermann",
                role = "Recruiter",
                company = CompanyId(companyUuid),
                channels = listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example", "work")),
                relationshipNotes = "Met at the fair",
            ),
            version = 2,
            createdAt = at,
            updatedAt = at.plusSeconds(60),
        )

    @Test
    fun `a contact becomes a response with every field`() {
        val response = ContactResponse.from(contact)

        response shouldBe
            ContactResponse(
                id = uuid,
                name = "Erika Mustermann",
                role = "Recruiter",
                companyId = companyUuid,
                channels = listOf(ContactChannelDto(ContactChannelKind.EMAIL, "erika@acme.example", "work")),
                relationshipNotes = "Met at the fair",
                version = 2,
                createdAt = at,
                updatedAt = at.plusSeconds(60),
            )
        ContactPageResponse.from(CompanyPage(listOf(contact), total = 5), 0, 1) shouldBe
            ContactPageResponse(listOf(response), page = 0, size = 1, total = 5)
        listOf(response, response.channels.single(), ContactDetailsRequest("Erika", "Recruiter")).forEach {
            it.toString() shouldNotContain "Erika"
            it.toString() shouldNotContain "erika@"
            it.toString() shouldNotContain "Recruiter"
        }
    }
}
