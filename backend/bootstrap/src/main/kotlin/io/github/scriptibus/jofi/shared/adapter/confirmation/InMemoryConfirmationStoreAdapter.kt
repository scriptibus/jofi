// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.confirmation

import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Pending confirmations in memory (ADR-0039). Only the `app` container serves requests, confirmations
 * live for minutes, and a restart only means confirming again, so they need no database. Entries are
 * keyed by the SHA-256 of their token: the raw token is kept nowhere on the server, and the map
 * lookup reveals nothing about a valid token. One lock makes issue and redeem atomic, so a token
 * works once even under parallel requests. At most [MAX_PENDING] entries: expired ones go first,
 * then the oldest (its user simply confirms again).
 */
@Component
class InMemoryConfirmationStoreAdapter : ConfirmationStorePort {
    private val random = SecureRandom()
    private val entries = LinkedHashMap<String, PendingConfirmation>()

    @Synchronized
    override fun issue(
        pending: PendingConfirmation,
        now: Instant,
    ): ConfirmationToken {
        entries.values.removeIf { !now.isBefore(it.expiresAt) }
        while (entries.size >= MAX_PENDING) entries.remove(entries.keys.first())
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = ConfirmationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        entries[key(token)] = pending
        return token
    }

    @Synchronized
    override fun redeem(token: ConfirmationToken): PendingConfirmation? = entries.remove(key(token))

    private fun key(token: ConfirmationToken): String {
        val digest = MessageDigest.getInstance(DIGEST).digest(token.value.toByteArray())
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        /** The most first steps kept at once. */
        const val MAX_PENDING = 1_000
        private const val TOKEN_BYTES = 32
        private const val DIGEST = "SHA-256"
    }
}
