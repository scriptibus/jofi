// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor
import java.security.MessageDigest
import java.time.Instant

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
            val effect = action.effect
            val counts = effect.counts.toSortedMap().flatMap { (name, count) -> listOf(name, count.toString()) }
            val fields =
                listOf(actorKey(requester.actor), requester.session, action.operation) +
                    counted(action.targets) + effect.kind + effect.name + counted(counts)
            val canonical = fields.joinToString("") { "${it.length}:$it" }
            return ConfirmationBinding(MessageDigest.getInstance(ALGORITHM).digest(canonical.toByteArray()))
        }

        private fun counted(values: List<String>): List<String> = listOf(values.size.toString()) + values

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

/**
 * A first step waiting for its confirmation until [expiresAt] (exclusive). It keeps only the digest,
 * no session id or action details. Only `ConfirmActionUseCase` creates and checks these
 * (architecture test), so the gate is the only code that can produce a [ConfirmationResult.Confirmed].
 */
class PendingConfirmation(
    val binding: ConfirmationBinding,
    val expiresAt: Instant,
) {
    /** Whether a second step of [requester] for [action] at [now] confirms this one. */
    fun check(
        requester: ConfirmationRequester,
        action: ConfirmableAction,
        now: Instant,
    ): ConfirmationResult =
        when {
            !now.isBefore(expiresAt) -> {
                ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)
            }

            !binding.matches(ConfirmationBinding.of(requester, action)) -> {
                ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)
            }

            else -> {
                ConfirmationResult.Confirmed(action)
            }
        }
}
