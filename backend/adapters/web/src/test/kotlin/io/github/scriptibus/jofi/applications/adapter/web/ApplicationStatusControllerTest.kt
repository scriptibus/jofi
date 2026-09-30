// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.DeclineCategory
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.StatusChange
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
 * The status endpoints (#84) over the real use cases with mocked repositories: the move with its freeze and
 * event, the decline reason, the 409s and 400s, and the history. Security is tested in bootstrap.
 */
@WebMvcTest(ApplicationController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
@Import(ApplicationControllerTest.UseCases::class)
class ApplicationStatusControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val ports: ApplicationControllerTest.Ports,
) {
    private val stored =
        Application.create(
            ApplicationId(UUID.fromString("00000000-0000-0000-0000-0000000000a1")),
            ApplicationDetails("Backend Engineer", CompanyRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"))),
            Instant.parse("2026-09-30T08:00:00Z"),
        )
    private val path = "/api/applications/${stored.id.value}"
    private val snapshot = SnapshotId(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))

    @BeforeEach
    fun storeOne() {
        clearMocks(ports.applications, ports.snapshots, ports.changelog, ports.events)
        every { ports.applications.findById(any()) } returns ApplicationStoreResult.NotFound
        every { ports.applications.findById(stored.id) } returns ApplicationStoreResult.Success(stored)
        every { ports.applications.changeStatus(any(), any()) } returns ApplicationStoreResult.Success(Unit)
        every { ports.snapshots.freeze(stored.id, any()) } returns ApplicationStoreResult.Success(listOf(snapshot))
        every { ports.applications.statusHistory(any()) } returns ApplicationStoreResult.NotFound
        every { ports.changelog.append(any()) } returns ChangelogResult.Success(Unit)
        every { ports.events.publish(any()) } returns true
    }

    @Test
    fun `changing the status answers the moved application and freezes the descriptions on applying`() {
        json(mvc.put().uri("$path/status"), """{"status":"APPLIED","reason":"Sent the CV","basedOnVersion":0}""")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"APPLIED","version":1}""")
        verify {
            ports.applications.changeStatus(
                match { it.status == ApplicationStatus.APPLIED },
                match { it.reason == "Sent the CV" && it.actor == Actor.User },
            )
        }
        verify { ports.snapshots.freeze(stored.id, any()) }
        verify { ports.changelog.append(match { it.entity == snapshot.toEntityRef() && it.actor == Actor.User }) }
        verify { ports.events.publish(match { it is ApplicationStatusChanged && it.to == ApplicationStatus.APPLIED }) }
    }

    @Test
    fun `declining sets the decline reason, which the response carries`() {
        json(
            mvc.put().uri("$path/status"),
            """{"status":"DECLINED","reason":"Too far","declineCategory":"LOCATION","basedOnVersion":0}""",
        ).assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"status":"DECLINED","declineReason":{"category":"LOCATION","text":"Too far"}}""")
        verify(exactly = 0) { ports.snapshots.freeze(any(), any()) }
    }

    @Test
    fun `a move the matrix forbids, a stale version and a missing category are refused`() {
        json(mvc.put().uri("$path/status"), """{"status":"ACCEPTED","basedOnVersion":0}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.INVALID_TRANSITION)
        json(mvc.put().uri("$path/status"), """{"status":"APPLIED","basedOnVersion":3}""")
            .assertThat()
            .hasStatus(409)
            .bodyJson()
            .extractingPath("type")
            .isEqualTo(ApplicationProblems.VERSION_CONFLICT)
        json(mvc.put().uri("$path/status"), """{"status":"REJECTED","basedOnVersion":0}""")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo("""{"violations":[{"field":"declineCategory","problem":"REQUIRED"}]}""")
        json(
            mvc.put().uri("/api/applications/${UUID.randomUUID()}/status"),
            """{"status":"APPLIED","basedOnVersion":0}""",
        ).assertThat()
            .hasStatus(404)
        verify(exactly = 0) { ports.applications.changeStatus(any(), any()) }
        verify(exactly = 0) { ports.changelog.append(any()) }
    }

    @Test
    fun `the status history lists every change, oldest first, or answers 404`() {
        val changes =
            listOf(
                StatusChange.initial(stored, Actor.User),
                StatusChange(
                    stored.id,
                    ApplicationStatus.DISCOVERED,
                    ApplicationStatus.DECLINED,
                    "Too far",
                    DeclineCategory.LOCATION,
                    Actor.ExternalClient("claude-desktop"),
                    stored.createdAt,
                ),
            )
        every { ports.applications.statusHistory(stored.id) } returns ApplicationStoreResult.Success(changes)

        mvc
            .get()
            .uri("$path/status-history")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isStrictlyEqualTo(HISTORY)
        mvc
            .get()
            .uri("/api/applications/${UUID.randomUUID()}/status-history")
            .assertThat()
            .hasStatus(404)
    }

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private companion object {
        const val HISTORY = """
            {"changes":[
              {"from":null,"to":"DISCOVERED","reason":null,"declineCategory":null,
               "actor":{"kind":"USER","name":null},"at":"2026-09-30T08:00:00Z"},
              {"from":"DISCOVERED","to":"DECLINED","reason":"Too far","declineCategory":"LOCATION",
               "actor":{"kind":"EXTERNAL_CLIENT","name":"claude-desktop"},"at":"2026-09-30T08:00:00Z"}]}
            """
    }
}
