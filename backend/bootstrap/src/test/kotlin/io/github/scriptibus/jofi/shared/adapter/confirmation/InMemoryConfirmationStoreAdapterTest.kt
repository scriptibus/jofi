// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationBinding
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationEffect
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class InMemoryConfirmationStoreAdapterTest {
    private val store = InMemoryConfirmationStoreAdapter()
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val binding =
        ConfirmationBinding.of(
            ConfirmationRequester(Actor.User, "session"),
            ConfirmableAction("applications.delete", listOf("42"), ConfirmationEffect("application", "ACME")),
        )

    private fun pending(expiresAt: Instant = now.plusSeconds(300)) = PendingConfirmation(binding, expiresAt)

    @Test
    fun `tokens carry 256 random bits in URL-safe Base64 and never repeat`() {
        val tokens = List(1_000) { store.issue(pending(), now).value }

        tokens.toSet() shouldHaveSize 1_000
        tokens.forEach { it shouldMatch Regex("[A-Za-z0-9_-]{43}") }
    }

    @Test
    fun `a token redeems what it was issued for exactly once`() {
        val entry = pending()
        val token = store.issue(entry, now)

        store.redeem(token) shouldBe entry
        store.redeem(token).shouldBeNull()
    }

    @Test
    fun `parallel redeems of one token let only one through`() {
        val token = store.issue(pending(), now)
        val pool = Executors.newFixedThreadPool(PARALLEL)

        val winners =
            try {
                pool.invokeAll(List(PARALLEL) { Callable { store.redeem(token) } }).mapNotNull { it.get() }
            } finally {
                pool.shutdown()
            }

        winners shouldHaveSize 1
    }

    @Test
    fun `expired entries are dropped when new ones are issued`() {
        val expired = store.issue(pending(expiresAt = now), now)

        store.issue(pending(), now)

        store.redeem(expired).shouldBeNull()
    }

    @Test
    fun `the store is bounded and drops the oldest entry first`() {
        val oldest = store.issue(pending(), now)
        val secondEntry = pending()
        val second = store.issue(secondEntry, now)
        repeat(InMemoryConfirmationStoreAdapter.MAX_PENDING - 1) { store.issue(pending(), now) }

        store.redeem(oldest).shouldBeNull()
        store.redeem(second) shouldBe secondEntry
    }

    private companion object {
        const val PARALLEL = 16
    }
}
