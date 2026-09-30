// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponses
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.AuthenticationException
import java.net.URI
import java.time.Instant

class ConfirmationsTest {
    private val action = ConfirmableAction("applications.delete", listOf("42"), "application 42")

    @Test
    fun `a missing or blank header is a first step`() {
        Confirmations.token(null).shouldBeNull()
        Confirmations.token(" ").shouldBeNull()
        Confirmations.token("abc") shouldBe ConfirmationToken("abc")
    }

    @Test
    fun `the requester is the user in this session`() {
        val request = MockHttpServletRequest()
        val sessionId = request.getSession(true)?.id

        val requester = Confirmations.requester(request)

        requester.actor shouldBe Actor.User
        requester.session shouldBe sessionId
    }

    @Test
    fun `without a session there is no requester, the filter chain answers 401`() {
        shouldThrow<AuthenticationException> { Confirmations.requester(MockHttpServletRequest()) }
    }

    @Test
    fun `the first step is a 428 problem with token, expiry, operation and targets`() {
        val required =
            ConfirmationResult.Required(ConfirmationToken("t0k3n"), Instant.parse("2026-09-30T10:05:00Z"), action)

        val problem = Confirmations.problem(required)

        problem.statusCode shouldBe HttpStatus.PRECONDITION_REQUIRED
        problem.body.type shouldBe URI.create(Confirmations.REQUIRED)
        (problem.body as Confirmations.ConfirmationRequiredProblem).confirmationToken shouldBe "t0k3n"
        problem.toString() shouldNotContain "t0k3n"
        problem.message shouldNotContain "t0k3n"
        problem.body.properties shouldBe
            mapOf(
                "expiresAt" to "2026-09-30T10:05:00Z",
                "operation" to "applications.delete",
                "targets" to listOf("42"),
            )
    }

    @Test
    fun `every refused token is a 412 problem that names no token`() {
        ConfirmationRejection.entries.forEach { reason ->
            val problem = Confirmations.problem(ConfirmationResult.Rejected(reason))

            problem.statusCode shouldBe HttpStatus.PRECONDITION_FAILED
            problem.body.type shouldBe URI.create(Confirmations.INVALID)
            problem.body.detail.orEmpty() shouldNotContain "t0k3n"
        }
    }

    @Test
    fun `the contract documents 428 on every operation that takes the confirmation header`() {
        val confirmed =
            Operation()
                .responses(
                    ApiResponses(),
                ).addParametersItem(HeaderParameter().name(Confirmations.HEADER))
        val plain = Operation().responses(ApiResponses())
        val openApi =
            OpenAPI()
                .components(Components())
                .paths(Paths().addPathItem("/x", PathItem().delete(confirmed).get(plain)))

        OpenApiSpecApplication.ContractCustomizer().customise(openApi)

        confirmed.responses["428"]
            ?.content
            ?.get("application/problem+json")
            ?.schema
            ?.`$ref` shouldBe "#/components/schemas/ConfirmationRequiredProblem"
        plain.responses["428"].shouldBeNull()
        openApi.components.schemas.keys shouldBe setOf("ProblemDetail", "ConfirmationRequiredProblem")
    }
}
