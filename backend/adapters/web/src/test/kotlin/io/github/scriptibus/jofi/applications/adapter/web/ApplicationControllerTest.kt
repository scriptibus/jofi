// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ApplicationSearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The application endpoints exist with their contract (#76) and answer `501` problem details until the
 * use cases land (#82, #83, #90). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(ApplicationController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class ApplicationControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val id = "00000000-0000-0000-0000-0000000000a1"
    private val companyId = "00000000-0000-0000-0000-00000000000c"
    private val contactId = "00000000-0000-0000-0000-0000000000c1"
    private val details =
        """
        {"title":"Backend Engineer","companyId":"$companyId","location":"Berlin","remoteShare":60,
         "employmentType":"FULL_TIME","seniority":"SENIOR","deadline":"2026-10-31","howApplied":"PORTAL",
         "portalNotes":"Account: me@example.org",
         "payBand":{"min":70000,"max":85000.50,"currency":"EUR","period":"YEAR","source":"ESTIMATED",
                    "estimateBasis":"Similar roles in Berlin","estimateConfidence":"MEDIUM"},
         "languageAndTone":{"postingLanguage":"de","applicationLanguage":"en","formOfAddress":"DU","tone":"PERSONAL"},
         "declineReason":{"category":"SALARY","text":"Too low"},
         "offer":{"salary":{"amount":80000,"currency":"EUR","period":"YEAR"},"vacationDays":30,"startDate":"2027-01-01"}}
        """.trimIndent()

    @Test
    fun `searching applications is not implemented yet`() {
        notImplemented(
            mvc.get().uri(
                "/api/applications?search=backend&companyId=$companyId&contactId=$contactId&page=1&size=20",
            ),
        )
    }

    @Test
    fun `creating, reading, updating and deleting an application are not implemented yet`() {
        notImplemented(json(mvc.post().uri("/api/applications"), details))
        notImplemented(mvc.get().uri("/api/applications/$id"))
        notImplemented(json(mvc.put().uri("/api/applications/$id"), """{"details":$details,"basedOnVersion":3}"""))
        notImplemented(mvc.delete().uri("/api/applications/$id").header(Confirmations.HEADER, "t0k3n"))
    }

    @Test
    fun `marking read and linking contacts are not implemented yet`() {
        notImplemented(json(mvc.put().uri("/api/applications/$id/unread"), """{"unread":false}"""))
        notImplemented(
            json(
                mvc.put().uri("/api/applications/$id/contacts"),
                """{"contactIds":["$contactId"],"basedOnVersion":3}""",
            ),
        )
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(mvc.get().uri("/api/applications?companyId=acme"))
        badRequest(mvc.get().uri("/api/applications/not-a-uuid"))
        badRequest(json(mvc.post().uri("/api/applications"), """{"title":"Backend Engineer"}"""))
        badRequest(
            json(mvc.post().uri("/api/applications"), """{"title":"X","companyId":"$companyId","seniority":"GURU"}"""),
        )
        badRequest(json(mvc.put().uri("/api/applications/$id"), """{"details":$details}"""))
        badRequest(json(mvc.put().uri("/api/applications/$id/contacts"), """{"contactIds":["$contactId"]}"""))
    }

    @Test
    fun `search parameters out of range are a 400 naming them`() {
        mvc
            .get()
            .uri("/api/applications?page=-1&size=${ApplicationSearch.MAX_SIZE + 1}")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${ApplicationProblems.INVALID_SEARCH}",
                 "violations":[{"field":"page","problem":"OUT_OF_RANGE"},{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
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
