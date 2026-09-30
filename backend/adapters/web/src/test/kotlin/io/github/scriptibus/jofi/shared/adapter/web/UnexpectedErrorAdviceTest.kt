// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException

class UnexpectedErrorAdviceTest {
    private val advice = UnexpectedErrorAdvice()

    @ResponseStatus(HttpStatus.CONFLICT, reason = "Already exists")
    private class AnnotatedException : RuntimeException("internal message")

    @ResponseStatus(HttpStatus.GONE)
    private open class AnnotatedWithoutReasonException : RuntimeException()

    private class SubclassOfAnnotatedException : AnnotatedWithoutReasonException()

    @Test
    fun `an unexpected exception becomes a 500 problem without internal details`() {
        val response = advice.handleUnexpected(IllegalStateException("secret internals"))

        response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
        response.body?.status shouldBe 500
        response.body?.detail shouldBe "Unexpected server error"
        response.body.toString() shouldNotContain "secret"
    }

    @Test
    fun `an ErrorResponse keeps its status and problem`() {
        val exception = ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Bad input")

        val response = advice.handleUnexpected(exception)

        response.statusCode shouldBe HttpStatus.UNPROCESSABLE_CONTENT
        response.body shouldBe exception.body
    }

    @Test
    fun `an exception annotated with ResponseStatus keeps that status and reason`() {
        val response = advice.handleUnexpected(AnnotatedException())

        response.statusCode shouldBe HttpStatus.CONFLICT
        response.body?.status shouldBe 409
        response.body?.detail shouldBe "Already exists"
    }

    @Test
    fun `the ResponseStatus annotation is found on a superclass and a blank reason stays empty`() {
        val response = advice.handleUnexpected(SubclassOfAnnotatedException())

        response.statusCode shouldBe HttpStatus.GONE
        response.body?.detail.shouldBeNull()
    }

    @Test
    fun `access denied is rethrown so the security filter chain answers 403`() {
        val denied = AccessDeniedException("denied")

        shouldThrow<AccessDeniedException> { advice.handleUnexpected(denied) } shouldBeSameInstanceAs denied
    }

    @Test
    fun `an authentication failure is rethrown so the security filter chain answers 401`() {
        val unauthenticated = InsufficientAuthenticationException("log in")

        shouldThrow<InsufficientAuthenticationException> {
            advice.handleUnexpected(
                unauthenticated,
            )
        } shouldBeSameInstanceAs
            unauthenticated
    }

    @Test
    fun `an ErrorResponse keeps its headers`() {
        val exception = ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)
        exception.headers.set(HttpHeaders.RETRY_AFTER, "5")

        advice.handleUnexpected(exception).headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBe "5"
    }
}
