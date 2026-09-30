// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant
import java.util.UUID

/**
 * The contact links (#90) over the real use case with mocked repositories: the replaced set, the no-op, and
 * the 400s, 404 and 409. Security is tested in bootstrap.
 */
@WebMvcTest(ApplicationContactsController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationControllerTest.UseCases::class)
class ApplicationContactsControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: ApplicationControllerTest.Ports,
) {
    private val anna = "00000000-0000-0000-0000-0000000000c1"
    private val ben = "00000000-0000-0000-0000-0000000000c2"
    private val company = CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))
    private val stored =
        Application
            .create(
                ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
                ApplicationDetails("Backend Engineer", company),
                Instant.parse("2026-09-30T08:00:00Z"),
            ).copy(contacts = setOf(ContactRef(UUID.fromString(anna))), version = 3)
    private val path = "/api/applications/${stored.id.value}/contacts"

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.snapshots, ports.changelog, ports.events)
        every { ports.applications.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.applications.findById(stored.id) } returns ApplicationStoreResult.Success(stored)
        every { ports.applications.replaceContacts(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `linking answers the application with exactly the sent contacts, recorded as the user`() {
        put("""{"contactIds":["$ben","$anna","$ben"],"basedOnVersion":3}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"contactIds":["$anna","$ben"],"version":4}""")
        verify { ports.applications.replaceContacts(match { it.contacts.size == 2 && it.version == 4L }) }
        verify {
            ports.changelog.append(
                match {
                    it.actor == Actor.User && it.change.fieldChanges
                        .single()
                        .after == ben
                },
            )
        }
    }

    @Test
    fun `sending the linked set again changes nothing`() {
        put("""{"contactIds":["$anna"],"basedOnVersion":3}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"contactIds":["$anna"],"version":3}""")
        verify(exactly = 0) { ports.applications.replaceContacts(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `an unknown contact is a 400 on contactIds`() {
        every { ports.applications.replaceContacts(any()) } returns ApplicationStoreResult.ContactNotFound

        put("""{"contactIds":["$ben"],"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"contactIds","problem":"NOT_FOUND"}]}""")
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `more than 50 contacts are a 400 on contactIds`() {
        val ids = (1..51).joinToString(",") { "\"${UUID.randomUUID()}\"" }

        put("""{"contactIds":[$ids],"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"contactIds","problem":"TOO_MANY"}]}""")
        verify(exactly = 0) { ports.applications.replaceContacts(any()) }
    }

    @Test
    fun `a stale version is a 409 and an unknown application a 404`() {
        put("""{"contactIds":[],"basedOnVersion":2}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.VERSION_CONFLICT)
        mvc
            .put()
            .uri("/api/applications/${UUID.randomUUID()}/contacts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"contactIds":[],"basedOnVersion":0}""")
            .assertThat()
            .hasStatus(404)
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"contactIds":["not-a-uuid"],"basedOnVersion":3}""")
            .assertThat()
            .hasStatus(400)
        put("""{"contactIds":["$ben"]}""").assertThat().hasStatus(400)
        verify(exactly = 0) { ports.applications.replaceContacts(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    private fun put(body: String): MockMvcTester.MockMvcRequestBuilder =
        mvc
            .put()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
}
