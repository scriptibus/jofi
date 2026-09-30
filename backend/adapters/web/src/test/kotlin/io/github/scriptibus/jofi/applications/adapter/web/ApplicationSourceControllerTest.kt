// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The source and description endpoints exist with their contract (#78) and answer `501` problem details until
 * the use cases land (#86, #96). Security is the filter chain's job (bootstrap tests).
 */
@WebMvcTest(ApplicationSourceController::class, properties = ["spring.mvc.problemdetails.enabled=true"])
@AutoConfigureMockMvc(addFilters = false)
class ApplicationSourceControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    private val base = "/api/applications/00000000-0000-0000-0000-0000000000a1"
    private val sourceId = "00000000-0000-0000-0000-0000000000d1"
    private val snapshotId = "00000000-0000-0000-0000-0000000000e1"
    private val otherSnapshotId = "00000000-0000-0000-0000-0000000000e2"

    @Test
    fun `adding a source and recording a description are not implemented yet`() {
        notImplemented(
            json(
                mvc.post().uri("$base/sources"),
                """
                {"kind":"URL","originalUrl":"https://jobs.example/1","discoveredAt":"2026-09-30T08:00:00Z",
                 "description":"# Backend Engineer\n\nKotlin, Berlin"}
                """.trimIndent(),
            ),
        )
        notImplemented(json(mvc.post().uri("$base/sources"), """{"kind":"MANUAL_CHAT"}"""))
        notImplemented(json(mvc.post().uri("$base/sources/$sourceId/snapshots"), """{"description":"New text"}"""))
    }

    @Test
    fun `reading versions and their diff are not implemented yet`() {
        notImplemented(mvc.get().uri("$base/sources/$sourceId/snapshots"))
        notImplemented(mvc.get().uri("$base/snapshots/$snapshotId"))
        notImplemented(mvc.get().uri("$base/description-diff?from=$snapshotId&to=$otherSnapshotId"))
    }

    @Test
    fun `requests that break the contract are rejected before the stub`() {
        badRequest(json(mvc.post().uri("$base/sources"), """{"originalUrl":"https://jobs.example/1"}"""))
        badRequest(json(mvc.post().uri("$base/sources"), """{"kind":"LINKEDIN"}"""))
        badRequest(json(mvc.post().uri("$base/sources/$sourceId/snapshots"), """{}"""))
        badRequest(mvc.get().uri("$base/sources/not-a-uuid/snapshots"))
        badRequest(mvc.get().uri("$base/description-diff?from=$snapshotId"))
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
