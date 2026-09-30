// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The company endpoints exist with their contract (#73) and answer `501` problem details until the
 * use cases land (#88). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(CompanyController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class CompanyControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val id = "00000000-0000-0000-0000-00000000000c"
    private val details = """{"name":"ACME GmbH","size":"MEDIUM","locations":["Berlin"]}"""

    @Test
    fun `searching companies is not implemented yet`() {
        notImplemented(mvc.get().uri("/api/companies?search=acme&preference=FAVOURITE&page=1&size=20"))
    }

    @Test
    fun `creating, reading and updating a company are not implemented yet`() {
        notImplemented(
            mvc
                .post()
                .uri("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(details),
        )
        notImplemented(mvc.get().uri("/api/companies/$id"))
        notImplemented(
            mvc
                .put()
                .uri("/api/companies/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"details":$details,"basedOnVersion":3}"""),
        )
    }

    @Test
    fun `setting the preference and deleting are not implemented yet`() {
        notImplemented(
            mvc
                .put()
                .uri("/api/companies/$id/preference")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"preference":"BLACKLISTED","reason":"Declined twice","basedOnVersion":3}"""),
        )
        notImplemented(mvc.delete().uri("/api/companies/$id").header(Confirmations.HEADER, "t0k3n"))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(mvc.get().uri("/api/companies?preference=MAYBE"))
        badRequest(mvc.get().uri("/api/companies/not-a-uuid"))
        badRequest(
            mvc
                .post()
                .uri("/api/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        badRequest(
            mvc
                .put()
                .uri("/api/companies/$id/preference")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"preference":"BLACKLISTED"}"""),
        )
    }

    @Test
    fun `search parameters out of range are a 400 naming them`() {
        mvc
            .get()
            .uri("/api/companies?size=${CompanySearch.MAX_SIZE + 1}")
            .assertThat()
            .hasStatus(400)
            .bodyJson()
            .isLenientlyEqualTo(
                """
                {"type":"${CompanyProblems.INVALID_SEARCH}",
                 "violations":[{"field":"size","problem":"OUT_OF_RANGE"}]}
                """.trimIndent(),
            )
    }

    private fun notImplemented(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(501).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
