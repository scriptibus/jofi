// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The task and countdown endpoints exist with their contract (#80) and answer `501` problem details until the use
 * cases land (#93, #94, #95, #112). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(TaskController::class, CountdownController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class TaskControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val one = "/api/tasks/00000000-0000-0000-0000-000000000011"
    private val countdown = "/api/countdowns/00000000-0000-0000-0000-000000000021"
    private val exact =
        """
        {"title":"Call back","timing":{"timeZone":"Europe/Berlin","localDue":"2026-10-05T10:00"},
         "link":{"type":"CONTACT","id":"00000000-0000-0000-0000-0000000000c1"},"notes":"# Ask"}
        """.trimIndent()
    private val bucket = """{"title":"Research","timing":{"timeZone":"+02:00","bucket":"NEXT_WEEK"}}"""
    private val version = """{"basedOnVersion":0}"""

    @Test
    fun `changing tasks is not implemented yet`() {
        notImplemented(json(mvc.post().uri("/api/tasks"), exact))
        notImplemented(json(mvc.post().uri("/api/tasks"), bucket))
        notImplemented(json(mvc.put().uri(one), """{"details":$bucket,"basedOnVersion":3}"""))
        listOf("complete", "reopen", "accept", "dismiss").forEach {
            notImplemented(json(mvc.post().uri("$one/$it"), version))
        }
        notImplemented(mvc.delete().uri(one))
        notImplemented(mvc.delete().uri(one).header(Confirmations.HEADER, "token"))
    }

    @Test
    fun `reading tasks is not implemented yet`() {
        notImplemented(mvc.get().uri("/api/tasks?timeZone=Europe/Berlin"))
        notImplemented(mvc.get().uri("/api/tasks/suggestions"))
        notImplemented(mvc.get().uri(one))
    }

    @Test
    fun `countdowns are not implemented yet`() {
        val request = """{"title":"Notice ends","targetDate":"2026-12-31"}"""
        notImplemented(mvc.get().uri("/api/countdowns"))
        notImplemented(json(mvc.post().uri("/api/countdowns"), request))
        notImplemented(json(mvc.put().uri(countdown), """{"details":$request,"basedOnVersion":0}"""))
        notImplemented(mvc.delete().uri(countdown))
        notImplemented(mvc.get().uri("/api/dashboard/countdowns?timeZone=UTC"))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(json(mvc.post().uri("/api/tasks"), """{"timing":{"timeZone":"UTC","bucket":"TODAY"}}"""))
        badRequest(json(mvc.post().uri("/api/tasks"), """{"title":"x","timing":{"timeZone":"UTC","bucket":"SOON"}}"""))
        badRequest(json(mvc.post().uri("/api/tasks"), """{"title":"x","timing":{"bucket":"TODAY"}}"""))
        badRequest(
            json(
                mvc.post().uri("/api/tasks"),
                """{"title":"x","timing":{"timeZone":"UTC"},"link":{"type":"JOB","id":"1"}}""",
            ),
        )
        badRequest(json(mvc.put().uri(one), """{"details":$bucket}"""))
        badRequest(json(mvc.post().uri("$one/complete"), "{}"))
        badRequest(mvc.get().uri("/api/tasks"))
        badRequest(mvc.get().uri("/api/tasks/not-a-uuid"))
        badRequest(json(mvc.post().uri("/api/countdowns"), """{"title":"x","targetDate":"soon"}"""))
        badRequest(mvc.get().uri("/api/dashboard/countdowns"))
    }

    private fun json(
        request: MockMvcTester.MockMvcRequestBuilder,
        body: String,
    ): MockMvcTester.MockMvcRequestBuilder = request.contentType(MediaType.APPLICATION_JSON).content(body)

    private fun notImplemented(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(501).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
