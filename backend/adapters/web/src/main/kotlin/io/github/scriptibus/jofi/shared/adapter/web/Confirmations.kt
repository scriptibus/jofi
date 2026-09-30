// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.web

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.web.ErrorResponseException
import java.net.URI

/**
 * The REST convention for two-step confirmations (ADR-0039), used by every destructive or
 * outward-facing endpoint:
 *
 * 1. The first call (without [HEADER]) runs nothing and answers `428 Precondition Required` with a
 *    [REQUIRED] problem carrying `confirmationToken`, `expiresAt`, `operation`, `targets` and the
 *    structured `effect` the client renders (and checks against what it meant to do).
 * 2. After the user confirmed, the client repeats the same call with the token in [HEADER]; only
 *    then does the operation run.
 * 3. A token that is unknown, used, expired or issued for another session, operation, target or
 *    effect answers `412 Precondition Failed` with an [INVALID] problem; the client starts over.
 *
 * The token travels in a header, never in the URL, so it stays out of access logs and history.
 */
object Confirmations {
    /** Request header that carries the token of the second step. */
    const val HEADER = "Jofi-Confirmation"
    const val REQUIRED = "urn:jofi:problem:shared:confirmation-required"
    const val INVALID = "urn:jofi:problem:shared:confirmation-invalid"

    /** The token of a second step, or `null` on a first step. */
    fun token(header: String?): ConfirmationToken? = header?.takeIf { it.isNotBlank() }?.let(::ConfirmationToken)

    /**
     * The user in this session. Without a session (which the filter chain already refuses) this is
     * a 401 from the security filter chain, never a confirmation that is bound to nothing.
     */
    fun requester(request: HttpServletRequest): ConfirmationRequester {
        val session =
            request.getSession(false)?.id
                ?: throw AuthenticationCredentialsNotFoundException("A confirmation needs a session")
        return ConfirmationRequester(Actor.User, session)
    }

    /** The answer for a call that must not run (yet); the controller throws it. */
    fun problem(outcome: ConfirmationResult.Unconfirmed): ErrorResponseException =
        when (outcome) {
            is ConfirmationResult.Required -> required(outcome)
            is ConfirmationResult.Rejected -> rejected(outcome.reason)
        }

    private fun required(outcome: ConfirmationResult.Required): ErrorResponseException {
        val effect = outcome.action.effect
        val body =
            ConfirmationRequiredProblem(
                outcome.token.value,
                ConfirmationEffectResponse(effect.kind, effect.name, effect.counts.toSortedMap()),
            )
        body.detail = "Confirm this action, then repeat the request with the token in the $HEADER header"
        body.type = URI.create(REQUIRED)
        body.setProperty("expiresAt", outcome.expiresAt.toString())
        body.setProperty("operation", outcome.action.operation)
        body.setProperty("targets", outcome.action.targets)
        return ErrorResponseException(HttpStatus.PRECONDITION_REQUIRED, body, null)
    }

    /**
     * Carries the token and the effect as serialized properties of their own, outside the extension
     * map that `ProblemDetail.toString()` prints: Spring logs resolved exceptions (message = problem)
     * at debug level, and neither the token nor names (personal data) may reach any log.
     */
    class ConfirmationRequiredProblem(
        val confirmationToken: String,
        val effect: ConfirmationEffectResponse,
    ) : ProblemDetail(HttpStatus.PRECONDITION_REQUIRED.value())

    /** What would change, for the client to render in the user's language (`ConfirmationEffect`). */
    data class ConfirmationEffectResponse(
        val kind: String,
        val name: String,
        val counts: Map<String, Int>,
    )

    private fun rejected(reason: ConfirmationRejection): ErrorResponseException {
        logger.warn("Confirmation refused: {}", reason)
        val detail =
            when (reason) {
                ConfirmationRejection.UNKNOWN -> "The confirmation is unknown or was already used"
                ConfirmationRejection.EXPIRED -> "The confirmation has expired"
                ConfirmationRejection.MISMATCH -> "The confirmation was issued for another request"
            }
        val body = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, "$detail; start again")
        body.type = URI.create(INVALID)
        return ErrorResponseException(HttpStatus.PRECONDITION_FAILED, body, null)
    }

    private val logger: Logger = LoggerFactory.getLogger(Confirmations::class.java)
}
