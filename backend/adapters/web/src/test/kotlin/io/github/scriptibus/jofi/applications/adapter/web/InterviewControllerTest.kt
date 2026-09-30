// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The interview endpoints exist with their contract (#79) and answer `501` problem details until the use cases land
 * (#91, #92). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(InterviewController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class InterviewControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val base = "/api/applications/00000000-0000-0000-0000-0000000000a1/interviews"
    private val one = "$base/00000000-0000-0000-0000-0000000000f1"
    private val details =
        """
        {"type":"TECHNICAL","localStart":"2026-10-05T10:00","timeZone":"Europe/Berlin",
         "participantIds":["00000000-0000-0000-0000-0000000000c1"],"preparationNotes":"# Prep",
         "notes":null,"outcome":"PASSED"}
        """.trimIndent()

    @Test
    fun `logging, editing and deleting an interview are not implemented yet`() {
        notImplemented(json(mvc.post().uri(base), details))
        notImplemented(
            json(mvc.post().uri(base), """{"type":"HR","localStart":"2026-10-05T10:00:30","timeZone":"+02:00"}"""),
        )
        notImplemented(json(mvc.put().uri(one), """{"details":$details,"basedOnVersion":0}"""))
        notImplemented(mvc.delete().uri(one))
        notImplemented(mvc.delete().uri(one).header(Confirmations.HEADER, "token"))
    }

    @Test
    fun `reading interviews is not implemented yet`() {
        notImplemented(mvc.get().uri(base))
        notImplemented(mvc.get().uri(one))
        notImplemented(mvc.get().uri("/api/interviews/upcoming"))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(json(mvc.post().uri(base), """{"localStart":"2026-10-05T10:00","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"LUNCH","localStart":"2026-10-05T10:00","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"HR","localStart":"next monday","timeZone":"UTC"}"""))
        badRequest(json(mvc.post().uri(base), """{"type":"HR","localStart":"2026-10-05T10:00"}"""))
        badRequest(json(mvc.put().uri(one), """{"details":$details}"""))
        badRequest(mvc.get().uri("$base/not-a-uuid"))
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
