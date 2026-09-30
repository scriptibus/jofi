// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor
import java.security.MessageDigest
import java.time.Instant

/**
 * Who asks for a destructive or outward-facing action, and through which session or client
 * connection. A confirmation only counts when it comes back from the same actor and session.
 * [session] is a credential (a session id or client token id), so [toString] leaves it out.
 */
data class ConfirmationRequester(
    val actor: Actor,
    val session: String,
) {
    init {
        require(session.isNotBlank()) { "A confirmation needs the session it is bound to" }
    }

    override fun toString(): String = "ConfirmationRequester(actor=$actor)"
}

/**
 * Exactly what would be executed: the [operation] (e.g. `applications.delete`), its [targets]
 * (entity ids) and the [effect], a description the feature derives from the current state (e.g.
 * "application 42 with 3 documents"). If any of them differs at the second step, for instance
 * because the target changed in between, the confirmation no longer matches.
 */
data class ConfirmableAction(
    val operation: String,
    val targets: List<String>,
    val effect: String,
) {
    init {
        require(operation.isNotBlank()) { "A confirmable action needs an operation" }
        require(targets.isNotEmpty() && targets.none { it.isBlank() }) { "A confirmable action needs its targets" }
    }
}

/** The unguessable, single-use proof of the first step. Never logged, so [toString] hides it. */
@JvmInline
value class ConfirmationToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A confirmation token must not be blank" }
    }

    override fun toString(): String = "ConfirmationToken(redacted)"
}

/**
 * A SHA-256 digest of requester and action, so the store keeps no session ids or action details and
 * a match is checked in constant time (`MessageDigest.isEqual`). Fields are length-prefixed, so no
 * two different requests encode to the same text.
 */
class ConfirmationBinding private constructor(
    private val digest: ByteArray,
) {
    fun matches(other: ConfirmationBinding): Boolean = MessageDigest.isEqual(digest, other.digest)

    companion object {
        private const val ALGORITHM = "SHA-256"

        fun of(
            requester: ConfirmationRequester,
            action: ConfirmableAction,
        ): ConfirmationBinding {
            val fields =
                listOf(actorKey(requester.actor), requester.session, action.operation) +
                    action.targets.size.toString() + action.targets + action.effect
            val canonical = fields.joinToString("") { "${it.length}:$it" }
            return ConfirmationBinding(MessageDigest.getInstance(ALGORITHM).digest(canonical.toByteArray()))
        }

        private fun actorKey(actor: Actor): String =
            when (actor) {
                Actor.User -> "user"
                Actor.Ai -> "ai"
                is Actor.Scanner -> "scanner/${actor.name}"
                is Actor.ExternalClient -> "external-client/${actor.name}"
                is Actor.System -> "system/${actor.name}"
            }
    }
}

/** A first step waiting for its confirmation until [expiresAt] (exclusive). */
class PendingConfirmation(
    val binding: ConfirmationBinding,
    val expiresAt: Instant,
) {
    /** Whether a second step for [candidate] at [now] confirms this one. */
    fun check(
        candidate: ConfirmationBinding,
        now: Instant,
    ): ConfirmationResult =
        when {
            !now.isBefore(expiresAt) -> ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)
            !binding.matches(candidate) -> ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)
            else -> ConfirmationResult.Confirmed
        }
}

/** One call of a destructive operation; [token] is absent on the first step. */
data class ConfirmationRequest(
    val requester: ConfirmationRequester,
    val action: ConfirmableAction,
    val token: ConfirmationToken?,
)

/** The outcome of the confirmation gate. Only [Confirmed] lets the operation run. */
sealed interface ConfirmationResult {
    /** The second step matched a pending first step: execute now. */
    data object Confirmed : ConfirmationResult

    /** Anything that must not execute; features pass it on to their caller unchanged. */
    sealed interface Unconfirmed : ConfirmationResult

    /** First step: nothing ran; show [action] to the user and repeat the call with [token]. */
    data class Required(
        val token: ConfirmationToken,
        val expiresAt: Instant,
        val action: ConfirmableAction,
    ) : Unconfirmed

    /** The token does not confirm this call; nothing ran and the token is spent. */
    data class Rejected(
        val reason: ConfirmationRejection,
    ) : Unconfirmed
}

/** Why a token was refused. */
enum class ConfirmationRejection {
    /** Never issued, already used, or dropped from the bounded store. */
    UNKNOWN,

    /** Issued, but its time ran out. */
    EXPIRED,

    /** Issued for another actor, session, operation, target or effect. */
    MISMATCH,
}
