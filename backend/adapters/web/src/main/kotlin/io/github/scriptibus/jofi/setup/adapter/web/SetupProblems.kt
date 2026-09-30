// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.web

import io.github.scriptibus.jofi.setup.domain.SetupField
import io.github.scriptibus.jofi.setup.domain.SetupResult
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.shared.adapter.web.FieldViolation
import io.github.scriptibus.jofi.shared.adapter.web.ValidationProblem
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * How setup failures answer over REST: a status and a `urn:jofi:problem:setup:*` type per case. No
 * key, URL or provider message ever goes into `detail`.
 */
object SetupProblems {
    const val INVALID = "urn:jofi:problem:setup:invalid-input"
    const val NOT_FOUND = "urn:jofi:problem:setup:provider-not-found"
    const val IN_USE = "urn:jofi:problem:setup:provider-in-use"
    const val FORBIDDEN = "urn:jofi:problem:setup:forbidden"
    const val AUTHENTICATION_FAILED = "urn:jofi:problem:setup:provider-authentication-failed"
    const val RATE_LIMITED = "urn:jofi:problem:setup:provider-rate-limited"
    const val UNREACHABLE = "urn:jofi:problem:setup:provider-unreachable"
    const val REJECTED = "urn:jofi:problem:setup:provider-rejected"
    const val UNAVAILABLE = "urn:jofi:problem:setup:storage-unavailable"

    fun of(failure: SetupResult.Failure): ErrorResponseException =
        when (failure) {
            is SetupResult.Invalid -> {
                ValidationProblem.of(
                    INVALID,
                    failure.violations.map { FieldViolation(apiName(it.field), it.problem.name) },
                )
            }

            SetupResult.NotFound -> {
                problem(HttpStatus.NOT_FOUND, NOT_FOUND, "No AI provider with this id")
            }

            SetupResult.InUse -> {
                problem(HttpStatus.CONFLICT, IN_USE, "Tasks are still assigned to this provider; reassign them first")
            }

            SetupResult.Forbidden -> {
                problem(HttpStatus.FORBIDDEN, FORBIDDEN, "Only the user may change the AI provider setup")
            }

            is SetupResult.Unconfirmed -> {
                Confirmations.problem(failure.outcome)
            }

            is SetupResult.ProviderFailed -> {
                providerFailed(failure.result)
            }

            is SetupResult.StorageFailure -> {
                problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "The AI setup cannot be stored right now")
            }
        }

    /** The request field a domain field arrives in. */
    fun apiName(field: SetupField): String =
        when (field) {
            SetupField.DISPLAY_NAME -> "displayName"
            SetupField.BASE_URL -> "baseUrl"
            SetupField.API_KEY -> "apiKey"
            SetupField.MODEL -> "model"
            SetupField.CONTEXT_WINDOW -> "contextWindowTokens"
        }

    // The provider is a server of the user's choosing, so its failures are a bad gateway, not ours.
    private fun providerFailed(result: AiResult<*>): ErrorResponseException =
        when (result) {
            AiResult.AuthenticationFailed -> {
                problem(HttpStatus.BAD_GATEWAY, AUTHENTICATION_FAILED, "The provider rejected the API key")
            }

            is AiResult.RateLimited -> {
                problem(HttpStatus.BAD_GATEWAY, RATE_LIMITED, "The provider throttled the request; try again later")
            }

            AiResult.Unavailable -> {
                problem(HttpStatus.BAD_GATEWAY, UNREACHABLE, "The provider could not be reached or is not allowed")
            }

            else -> {
                problem(HttpStatus.BAD_GATEWAY, REJECTED, "The provider refused to list its models")
            }
        }

    private fun problem(
        status: HttpStatus,
        type: String,
        detail: String,
    ): ErrorResponseException {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply { this.type = URI.create(type) }
        return ErrorResponseException(status, body, null)
    }
}
