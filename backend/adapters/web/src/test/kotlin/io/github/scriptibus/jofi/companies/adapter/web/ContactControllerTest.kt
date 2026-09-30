// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.application.CreateContactUseCase
import io.github.scriptibus.jofi.companies.application.DeleteContactUseCase
import io.github.scriptibus.jofi.companies.application.GetContactUseCase
import io.github.scriptibus.jofi.companies.application.SearchContactsUseCase
import io.github.scriptibus.jofi.companies.application.UpdateContactUseCase
import io.github.scriptibus.jofi.companies.application.port.ContactRepositoryPort
import io.github.scriptibus.jofi.companies.application.port.spi.LinkedApplicationsPort
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyPage
import io.github.scriptibus.jofi.companies.domain.Contact
import io.github.scriptibus.jofi.companies.domain.ContactChannel
import io.github.scriptibus.jofi.companies.domain.ContactDetails
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.companies.domain.ContactStoreResult
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The contact endpoints over the real use cases with mocked ports: mapping, problem details and the
 * two-step delete. Security (session, CSRF) is the filter chain's job, tested in bootstrap.
 */
@WebMvcTest(ContactController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ContactControllerTest.UseCases::class)
class ContactControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    /** The mocked ports behind the real use cases. */
    class Ports {
        val contacts = mockk<ContactRepositoryPort>()
        val applications = mockk<LinkedApplicationsPort>()
        val changelog = mockk<ChangelogPort>()
        val events = mockk<DomainEventPort>()
        val transactions =
            object : TransactionPort {
                override fun <T> inTransaction(
                    commitIf: (T) -> Boolean,
                    work: () -> T,
                ): T = work()
            }
    }

    @TestConfiguration
    class UseCases {
        private val clock = Clock.systemUTC()

        @Bean
        fun ports() = Ports()

        @Bean
        fun search(ports: Ports) = SearchContactsUseCase(ports.contacts)

        @Bean
        fun create(ports: Ports) = CreateContactUseCase(ports.contacts, ports.changelog, ports.transactions, clock)

        @Bean
        fun get(ports: Ports) = GetContactUseCase(ports.contacts)

        @Bean
        fun update(ports: Ports) = UpdateContactUseCase(ports.contacts, ports.changelog, ports.transactions, clock)

        @Bean
        fun delete(ports: Ports) =
            DeleteContactUseCase(
                ports.contacts,
                ports.applications,
                ConfirmActionUseCase(MapStore(), clock, Duration.ofMinutes(5)),
                ports.events,
                ports.changelog,
                ports.transactions,
                clock,
            )
    }

    private class MapStore : ConfirmationStorePort {
        private val pending = mutableMapOf<String, PendingConfirmation>()

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ) = ConfirmationToken(UUID.randomUUID().toString()).also { this.pending[it.value] = pending }

        override fun redeem(token: ConfirmationToken) = pending.remove(token.value)
    }

    private val json = JsonMapper.builder().build()
    private val acme = CompanyId(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val erika =
        Contact.create(
            ContactId(UUID.fromString("00000000-0000-0000-0000-0000000000c1")),
            ContactDetails(
                "Erika Mustermann",
                "Recruiter",
                acme,
                listOf(ContactChannel(ChannelKind.EMAIL, "erika@acme.example", "work")),
            ),
            Instant.parse("2026-09-30T08:00:00Z"),
        )
    private val path = "/api/contacts/${erika.id.value}"
    private val details =
        """
        {"name":"Erika Mustermann","role":"Recruiter","companyId":"${acme.value}",
         "channels":[{"kind":"EMAIL","value":"erika@acme.example","label":"work"},{"kind":"PHONE","value":"030 123"}],
         "relationshipNotes":"Met at the fair"}
        """.trimIndent()

    @BeforeEach
    fun storeErika() {
        clearMocks(ports.contacts, ports.applications, ports.changelog, ports.events)
        every { ports.contacts.findById(any()) } returns ContactStoreResult.NotFound
        every { ports.contacts.findById(erika.id) } returns ContactStoreResult.Success(erika)
        every { ports.contacts.add(any()) } returns ContactStoreResult.Success(Unit)
        every { ports.contacts.update(any()) } returns ContactStoreResult.Success(Unit)
        every { ports.contacts.delete(any(), any()) } returns ContactStoreResult.Success(Unit)
        every { ports.applications.linkedTo(erika.id.value) } returns
            LinkedApplicationsPort.Linked.Found(listOf(EntityRef("application", UUID.randomUUID().toString())))
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.events.publish(any()) } returns true
    }

    @Test
    fun `searching answers a page of contacts with their channels`() {
        val search = slot<ContactSearch>()
        every { ports.contacts.search(capture(search)) } returns
            ContactStoreResult.Success(CompanyPage(listOf(erika), 21))

        mvc
            .get()
            .uri("/api/contacts?search= erika &companyId=${acme.value}&page=1&size=20")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"contacts":[{"id":"${erika.id.value}","name":"Erika Mustermann","companyId":"${acme.value}",
                  "channels":[{"kind":"EMAIL","value":"erika@acme.example","label":"work"}],"version":0}],
                 "page":1,"size":20,"total":21}
                """.trimIndent(),
            )
        search.captured shouldBe ContactSearch("erika", acme, 1, 20)
    }

    @Test
    fun `search parameters out of range and malformed values are a 400`() {
        mvc
            .get()
            .uri("/api/contacts?page=-1&size=${ContactSearch.MAX_SIZE + 1}")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ContactProblems.INVALID_SEARCH}",
                 "violations":[{"field":"page","problem":"OUT_OF_RANGE"},{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
        mvc
            .get()
            .uri("/api/contacts?companyId=acme")
            .assertThat()
            .hasStatus(400)
        mvc
            .get()
            .uri("/api/contacts/not-a-uuid")
            .assertThat()
            .hasStatus(400)
    }

    @Test
    fun `creating answers 201 with the new contact, recorded as the user without personal data`() {
        mvc
            .post()
            .uri("/api/contacts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(details)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"name":"Erika Mustermann","role":"Recruiter","companyId":"${acme.value}",
                 "channels":[{"kind":"EMAIL","label":"work"},{"kind":"PHONE","value":"030 123"}],"version":0}
                """.trimIndent(),
            )
        verify {
            ports.changelog.append(
                match {
                    it.actor == Actor.User && !it.change.toString().contains("Erika") &&
                        it.change.fieldChanges.isEmpty()
                },
            )
        }
    }

    @Test
    fun `invalid details are a 400 naming the request fields`() {
        mvc
            .post()
            .uri("/api/contacts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":" ","channels":[{"kind":"PHONE","value":"030"},{"kind":"EMAIL","value":"erika"}]}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ContactProblems.INVALID}","violations":[
                  {"field":"channels[1].value","problem":"INVALID_EMAIL"},{"field":"name","problem":"REQUIRED"}]}
                """.trimIndent(),
            )
        verify(exactly = 0) { ports.contacts.add(any()) }
    }

    @Test
    fun `an unknown company or channel kind is a 400`() {
        every { ports.contacts.add(any()) } returns ContactStoreResult.CompanyNotFound
        mvc
            .post()
            .uri("/api/contacts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(details)
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"companyId","problem":"NOT_FOUND"}]}""")
        mvc
            .post()
            .uri("/api/contacts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Erika","channels":[{"kind":"FAX","value":"030 123"}]}""")
            .assertThat()
            .hasStatus(400)
    }

    @Test
    fun `reading an unknown contact is a 404`() {
        mvc
            .get()
            .uri("/api/contacts/${UUID.randomUUID()}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ContactProblems.NOT_FOUND)
        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatusOk()
    }

    @Test
    fun `updating replaces details and channels, a stale version is a 409`() {
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"details":{"name":"Erika Mustermann"},"basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"name":"Erika Mustermann","channels":[],"version":1}""")
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"details":$details,"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ContactProblems.VERSION_CONFLICT)
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"details":$details}""")
            .assertThat()
            .hasStatus(400)
    }

    @Test
    fun `deleting takes two steps and the effect counts the linked applications`() {
        val session = MockHttpSession()

        val first = deleteErika(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe
            """{"kind":"contact","name":"Erika Mustermann","counts":{"applications":1}}"""
        verify(exactly = 0) { ports.contacts.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        deleteErika(session, token).response.status shouldBe 204
        verify { ports.contacts.delete(erika.id, any()) }
        verify { ports.events.publish(any()) }
        deleteErika(session, token).response.status shouldBe 412
    }

    @Test
    fun `a store that cannot answer is a 503 without details`() {
        every { ports.contacts.findById(erika.id) } returns ContactStoreResult.StorageFailure("findById")

        mvc
            .get()
            .uri(path)
            .assertThat()
            .hasStatus(503)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(CompanyProblems.UNAVAILABLE)
    }

    private fun deleteErika(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri(path)
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()
}
