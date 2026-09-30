// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.web

import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The contact endpoints exist with their contract (#74) and answer `501` problem details until the
 * use cases land (#89). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(ContactController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class ContactControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val id = "00000000-0000-0000-0000-0000000000c1"
    private val companyId = "00000000-0000-0000-0000-00000000000c"
    private val details =
        """
        {"name":"Erika Mustermann","role":"Recruiter","companyId":"$companyId",
         "channels":[{"kind":"EMAIL","value":"erika@acme.example","label":"work"},{"kind":"PHONE","value":"030 123"}],
         "relationshipNotes":"Met at the fair"}
        """.trimIndent()

    @Test
    fun `searching contacts is not implemented yet`() {
        notImplemented(mvc.get().uri("/api/contacts?search=erika&companyId=$companyId&page=1&size=20"))
    }

    @Test
    fun `creating, reading, updating and deleting a contact are not implemented yet`() {
        notImplemented(
            mvc
                .post()
                .uri("/api/contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(details),
        )
        notImplemented(mvc.get().uri("/api/contacts/$id"))
        notImplemented(
            mvc
                .put()
                .uri("/api/contacts/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"details":$details,"basedOnVersion":3}"""),
        )
        notImplemented(mvc.delete().uri("/api/contacts/$id").header(Confirmations.HEADER, "t0k3n"))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(mvc.get().uri("/api/contacts?companyId=acme"))
        badRequest(mvc.get().uri("/api/contacts/not-a-uuid"))
        badRequest(
            mvc
                .post()
                .uri("/api/contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        badRequest(
            mvc
                .post()
                .uri("/api/contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Erika","channels":[{"kind":"FAX","value":"030 123"}]}"""),
        )
        badRequest(
            mvc
                .put()
                .uri("/api/contacts/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"details":$details}"""),
        )
    }

    @Test
    fun `search parameters out of range are a 400 naming them`() {
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
    }

    private fun notImplemented(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(501).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private fun badRequest(request: MockMvcTester.MockMvcRequestBuilder) {
        request.assertThat().hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
