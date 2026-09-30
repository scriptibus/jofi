// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/**
 * The SPA shell without a session (`src/test/resources/static` stands in for the frontend build):
 * client routes get `index.html`, assets are served, and the API keeps its 401/404 problem details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class SpaRoutingTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    @Test
    fun `deep links and the share target open the SPA`() {
        // "/" is Spring Boot's welcome page, a forward to index.html that MockMvc does not follow.
        mvc
            .get()
            .uri("/")
            .assertThat()
            .hasStatusOk()
            .hasForwardedUrl("index.html")
        listOf("/applications", "/settings/password", "/share?url=https%3A%2F%2Fexample.com%2Fjob").forEach { uri ->
            mvc
                .get()
                .uri(uri)
                .assertThat()
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.TEXT_HTML)
                .bodyText()
                .contains("jofi-spa-test-shell")
        }
    }

    @Test
    fun `assets are served and missing ones stay 404`() {
        mvc
            .get()
            .uri("/assets/app.js")
            .assertThat()
            .hasStatusOk()
            .bodyText()
            .contains("jofi-spa-test-asset")
        mvc
            .get()
            .uri("/assets/missing.js")
            .assertThat()
            .hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `API paths never fall back to the SPA`() {
        mvc
            .get()
            .uri("/api/system/info")
            .assertThat()
            .hasStatus(HttpStatus.UNAUTHORIZED)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
