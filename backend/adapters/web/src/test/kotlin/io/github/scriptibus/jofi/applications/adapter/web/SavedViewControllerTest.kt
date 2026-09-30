// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.CreateSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.DeleteSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.GetSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.ListSavedViewsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateSavedViewUseCase
import io.github.scriptibus.jofi.applications.application.port.SavedViewRepositoryPort
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.SavedView
import io.github.scriptibus.jofi.applications.domain.SavedViewDetails
import io.github.scriptibus.jofi.applications.domain.SavedViewFilter
import io.github.scriptibus.jofi.applications.domain.SavedViewId
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import java.time.ZoneOffset
import java.util.UUID

/**
 * The saved view endpoints (#99) over the real use cases with a mocked store: save, list, read, rename, the 400,
 * 404 and 409, and the two-step delete. Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(SavedViewController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(SavedViewControllerTest.UseCases::class)
class SavedViewControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: Ports,
) {
    class Ports {
        val views = mockk<SavedViewRepositoryPort>()
        val changelog = mockk<ChangelogPort>()
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
        @Bean
        fun ports() = Ports()

        @Bean
        fun list(ports: Ports) = ListSavedViewsUseCase(ports.views)

        @Bean
        fun create(ports: Ports) = CreateSavedViewUseCase(ports.views, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun get(ports: Ports) = GetSavedViewUseCase(ports.views)

        @Bean
        fun update(ports: Ports) = UpdateSavedViewUseCase(ports.views, ports.changelog, ports.transactions, CLOCK)

        @Bean
        fun delete(ports: Ports) =
            DeleteSavedViewUseCase(
                ports.views,
                ConfirmActionUseCase(MapStore(), CLOCK, Duration.ofMinutes(5)),
                ports.changelog,
                ports.transactions,
                CLOCK,
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
    private val views = "/api/applications/saved-views"
    private val stored =
        SavedView(
            SavedViewId(UUID.fromString("00000000-0000-0000-0000-0000000000f1")),
            SavedViewDetails("Offers", SavedViewFilter(text = "Kotlin", statuses = setOf(ApplicationStatus.OFFER))),
            2,
            NOW,
            NOW,
        )
    private val one = "$views/${stored.id.value}"
    private val view =
        """
        {"name":"Offers","filter":{"search":"Kotlin","companyId":"00000000-0000-0000-0000-0000000000a1",
         "status":["OFFER","APPLIED"],"unread":true,"language":["de"],"sourceKind":["URL"],
         "createdFrom":"2026-09-01T00:00:00Z","wantMin":3.5,"sort":"STATUS","direction":"DESCENDING"}}
        """.trimIndent()

    @BeforeEach
    fun stored() {
        clearMocks(ports.views, ports.changelog)
        every { ports.views.list() } returns ApplicationStoreResult.Success(listOf(stored))
        every { ports.views.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.views.findById(stored.id) } returns ApplicationStoreResult.Success(stored)
        every { ports.views.add(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.views.update(any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.views.delete(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
    }

    @Test
    fun `saving answers 201 with the view, its filter as the list's parameters, recorded as the user`() {
        every { ports.views.list() } returns ApplicationStoreResult.Success(emptyList())

        json(mvc.post().uri(views), view)
            .assertThat()
            .hasStatus(201)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"name":"Offers","adjusted":false,"version":0,"createdAt":"2026-09-30T08:00:00Z",
                   "filter":{"search":"Kotlin","companyId":"00000000-0000-0000-0000-0000000000a1","unread":true,
                   "language":["de"],"sourceKind":["URL"],"createdFrom":"2026-09-01T00:00:00Z","wantMin":3.5,
                   "sort":"STATUS","direction":"DESCENDING"}}""",
            )
        verify { ports.views.add(match { it.details.name == "Offers" }) }
        verify { ports.changelog.append(match { it.actor == Actor.User }) }
    }

    @Test
    fun `listing and reading answer the stored views`() {
        mvc
            .get()
            .uri(views)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"views":[{"id":"${stored.id.value}","name":"Offers","adjusted":false,"version":2,
                   "filter":{"search":"Kotlin","status":["OFFER"]}}]}""",
            )
        mvc
            .get()
            .uri(one)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("name")
            .isEqualTo("Offers")
        mvc
            .get()
            .uri("$views/${UUID.randomUUID()}")
            .assertThat()
            .hasStatus(404)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.SAVED_VIEW_NOT_FOUND)
    }

    @Test
    fun `a rename answers the next version, a stale version is a 409`() {
        put("""{"view":{"name":"Good offers","filter":{"search":"Kotlin","status":["OFFER"]}},"basedOnVersion":2}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo(
                """{"name":"Good offers","version":3,"filter":{"search":"Kotlin","status":["OFFER"]}}""",
            )
        put("""{"view":{"name":"Other"},"basedOnVersion":1}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.VERSION_CONFLICT)
        verify(exactly = 1) { ports.views.update(any()) }
    }

    @Test
    fun `a taken name or a filter the list refuses is a 400 naming the field`() {
        json(mvc.post().uri(views), """{"name":"OFFERS","filter":{"wantMin":6,"language":["german!"]}}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """{"type":"${ApplicationProblems.INVALID_VIEW}","violations":[
                   {"field":"filter.language","problem":"INVALID_LANGUAGE"},
                   {"field":"filter.wantMin","problem":"OUT_OF_RANGE"}]}""",
            )
        json(mvc.post().uri(views), """{"name":"OFFERS"}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .extractingPath("violations")
            .isEqualTo(listOf(mapOf("field" to "name", "problem" to "TAKEN")))
        verify(exactly = 0) { ports.views.add(any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `deleting takes two steps and the effect names the view`() {
        val session = MockHttpSession()

        val first = delete(session)
        first.response.status shouldBe 428
        val problem = json.readTree(first.response.contentAsString)
        problem["type"].asString() shouldBe Confirmations.REQUIRED
        problem["effect"].toString() shouldBe """{"kind":"saved_view","name":"Offers","counts":{}}"""
        verify(exactly = 0) { ports.views.delete(any(), any()) }

        val token = problem["confirmationToken"].asString()
        delete(session, token).response.status shouldBe 204
        verify { ports.views.delete(stored.id, any()) }
        delete(session, token).response.status shouldBe 412
    }

    @Test
    fun `requests that break the contract are rejected before the use case`() {
        badRequest(json(mvc.post().uri(views), """{"filter":{}}"""))
        badRequest(json(mvc.post().uri(views), """{"name":"x","filter":{"status":["SOMEWHERE"]}}"""))
        badRequest(json(mvc.put().uri(one), """{"view":$view}"""))
        badRequest(mvc.get().uri("$views/not-a-uuid"))
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    private fun put(body: String) = json(mvc.put().uri(one), body)

    private fun delete(
        session: MockHttpSession,
        token: String? = null,
    ) = mvc
        .delete()
        .uri(one)
        .session(session)
        .apply { if (token != null) header(Confirmations.HEADER, token) }
        .exchange()

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-30T08:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
