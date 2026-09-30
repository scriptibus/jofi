// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The countdown endpoints exist with their contract (#80) and answer `501` problem details until the use cases land
 * (#112). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(CountdownController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class CountdownControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val countdown = "/api/countdowns/00000000-0000-0000-0000-000000000021"

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
