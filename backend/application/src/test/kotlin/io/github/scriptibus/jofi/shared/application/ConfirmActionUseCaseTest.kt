// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application

import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmableAction
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRejection
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequest
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.shared.domain.confirmation.PendingConfirmation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ConfirmActionUseCaseTest {
    private val start = Instant.parse("2026-09-30T10:00:00Z")
    private var now = start
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }
    private val store = FakeStore()
    private val useCase = ConfirmActionUseCase(store, clock, TTL)

    private val user = ConfirmationRequester(Actor.User, "session-a")
    private val delete = ConfirmableAction("applications.delete", listOf("42"), "application 42")

    private fun firstStep(
        requester: ConfirmationRequester = user,
        action: ConfirmableAction = delete,
    ): ConfirmationResult.Required =
        useCase.execute(ConfirmationRequest(requester, action, null)).shouldBeInstanceOf<ConfirmationResult.Required>()

    private fun secondStep(
        token: ConfirmationToken,
        requester: ConfirmationRequester = user,
        action: ConfirmableAction = delete,
    ) = useCase.execute(ConfirmationRequest(requester, action, token))

    @Test
    fun `the first step never confirms, it issues a token for exactly this action`() {
        val required = firstStep()

        required.action shouldBe delete
        required.expiresAt shouldBe start.plus(TTL)
        store.issued shouldBe 1
    }

    @Test
    fun `the second step with the token confirms`() {
        val required = firstStep()

        secondStep(required.token) shouldBe ConfirmationResult.Confirmed
    }

    @Test
    fun `a token works only once`() {
        val required = firstStep()
        secondStep(required.token) shouldBe ConfirmationResult.Confirmed

        secondStep(required.token) shouldBe ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN)
    }

    @Test
    fun `a made-up token is refused`() {
        secondStep(ConfirmationToken("guessed")) shouldBe ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN)
    }

    @Test
    fun `a token is refused once it expired`() {
        val required = firstStep()
        now = start.plus(TTL)

        secondStep(required.token) shouldBe ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)
    }

    @Test
    fun `a token from another session or actor is refused and spent`() {
        val required = firstStep()
        val otherSession = user.copy(session = "session-b")

        secondStep(required.token, requester = otherSession) shouldBe
            ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)
        secondStep(required.token) shouldBe ConfirmationResult.Rejected(ConfirmationRejection.UNKNOWN)
    }

    @Test
    fun `a token cannot be retargeted to another operation, target or effect`() {
        val retargeted =
            listOf(
                delete.copy(operation = "applications.archive"),
                delete.copy(targets = listOf("43")),
                delete.copy(effect = "application 42 with a new document"),
            )

        retargeted.forEach { action ->
            secondStep(firstStep().token, action = action) shouldBe
                ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)
        }
        secondStep(firstStep().token, requester = user.copy(actor = Actor.ExternalClient("Claude"))) shouldBe
            ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)
    }

    @Test
    fun `the time to live must be positive`() {
        shouldThrow<IllegalArgumentException> { ConfirmActionUseCase(store, clock, Duration.ZERO) }
    }

    /** A map, like the in-memory adapter, minus randomness and bounds. */
    private class FakeStore : ConfirmationStorePort {
        private val entries = mutableMapOf<String, PendingConfirmation>()
        var issued = 0

        override fun issue(
            pending: PendingConfirmation,
            now: Instant,
        ): ConfirmationToken {
            issued++
            val token = "token-$issued"
            entries[token] = pending
            return ConfirmationToken(token)
        }

        override fun redeem(token: ConfirmationToken): PendingConfirmation? = entries.remove(token.value)
    }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(5)
    }
}
