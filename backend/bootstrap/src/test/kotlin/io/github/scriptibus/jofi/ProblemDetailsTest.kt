// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** The one error contract of the API (ADR-0031): every error is RFC 9457 problem details. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, ProblemDetailsTest.SealedResultController::class)
class ProblemDetailsTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    @Test
    fun `framework errors are problem details`() {
        mvc
            .get()
            .uri("/api/does-not-exist")
            .assertThat()
            .hasStatus(HttpStatus.NOT_FOUND)
            .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
            .bodyJson()
            .extractingPath("$.status")
            .isEqualTo(404)
    }

    @Test
    fun `a sealed failure result mapped by a controller is problem details`() {
        val body =
            mvc
                .get()
                .uri("/api/test/things/missing")
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()

        body.extractingPath("$.type").isEqualTo("urn:jofi:problem:test:thing-not-found")
        body.extractingPath("$.status").isEqualTo(404)
        body.extractingPath("$.detail").isEqualTo("No thing called missing")
    }

    @Test
    fun `the success path of the same controller stays a typed body`() {
        mvc
            .get()
            .uri("/api/test/things/known")
            .assertThat()
            .hasStatusOk()
            .hasContentType(MediaType.APPLICATION_JSON)
            .bodyJson()
            .isLenientlyEqualTo("""{"name":"known"}""")
    }

    /** What a use case returns: failures are values, not exceptions. */
    sealed interface ThingResult {
        data class Found(
            val name: String,
        ) : ThingResult

        data class NotFound(
            val name: String,
        ) : ThingResult
    }

    data class ThingResponse(
        val name: String,
    )

    /** The documented controller pattern; @TestComponent keeps it out of other tests' component scan. */
    @TestComponent
    @RestController
    class SealedResultController {
        @GetMapping("/api/test/things/{name}")
        fun getThing(
            @PathVariable name: String,
        ): ThingResponse =
            when (val result = find(name)) {
                is ThingResult.Found -> ThingResponse(result.name)
                is ThingResult.NotFound -> throw notFound(result)
            }

        private fun find(name: String): ThingResult =
            if (name == "known") ThingResult.Found(name) else ThingResult.NotFound(name)

        private fun notFound(result: ThingResult.NotFound): ErrorResponseException {
            val problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No thing called ${result.name}")
            problem.type = URI.create("urn:jofi:problem:test:thing-not-found")
            return ErrorResponseException(HttpStatus.NOT_FOUND, problem, null)
        }
    }
}
