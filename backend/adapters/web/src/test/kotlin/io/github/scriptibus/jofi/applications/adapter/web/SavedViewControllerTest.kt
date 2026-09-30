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
 * The saved view endpoints exist with their contract (#81) and answer `501` problem details until the use cases
 * land (#99). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(SavedViewController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class SavedViewControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val views = "/api/applications/saved-views"
    private val one = "$views/00000000-0000-0000-0000-0000000000f1"
    private val view =
        """
        {"name":"Offers","filter":{"search":"Kotlin","companyId":"00000000-0000-0000-0000-0000000000a1",
         "status":["OFFER","APPLIED"],"unread":true,"language":["de"],"sourceKind":["URL"],
         "createdFrom":"2026-09-01T00:00:00Z","wantMin":3.5,"sort":"STATUS","direction":"DESCENDING"}}
        """.trimIndent()

    @Test
    fun `saving, changing and deleting a view are not implemented yet`() {
        notImplemented(json(mvc.post().uri(views), view))
        notImplemented(json(mvc.post().uri(views), """{"name":"Everything"}"""))
        notImplemented(json(mvc.put().uri(one), """{"view":$view,"basedOnVersion":0}"""))
        notImplemented(mvc.delete().uri(one))
        notImplemented(mvc.delete().uri(one).header(Confirmations.HEADER, "token"))
    }

    @Test
    fun `reading views is not implemented yet`() {
        notImplemented(mvc.get().uri(views))
        notImplemented(mvc.get().uri(one))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(json(mvc.post().uri(views), """{"filter":{}}"""))
        badRequest(json(mvc.post().uri(views), """{"name":"x","filter":{"status":["SOMEWHERE"]}}"""))
        badRequest(json(mvc.put().uri(one), """{"view":$view}"""))
        badRequest(mvc.get().uri("$views/not-a-uuid"))
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
