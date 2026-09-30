// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfirmationTest {
    private val requester = ConfirmationRequester(Actor.User, "session-a")
    private val action = ConfirmableAction("applications.delete", listOf("42"), "application 42 with 3 documents")
    private val now = Instant.parse("2026-09-30T10:00:00Z")

    private fun binding(
        who: ConfirmationRequester = requester,
        what: ConfirmableAction = action,
    ) = ConfirmationBinding.of(who, what)

    @Test
    fun `the same requester and action bind to the same digest`() {
        binding().matches(binding()) shouldBe true
    }

    @Test
    fun `any other actor, session, operation, target or effect is a different binding`() {
        val others =
            listOf(
                binding(who = requester.copy(session = "session-b")),
                binding(who = requester.copy(actor = Actor.ExternalClient("Claude Desktop"))),
                binding(who = requester.copy(actor = Actor.Ai)),
                binding(what = action.copy(operation = "applications.archive")),
                binding(what = action.copy(targets = listOf("43"))),
                binding(what = action.copy(targets = listOf("42", "43"))),
                binding(what = action.copy(effect = "application 42 with 4 documents")),
            )

        others.forEach { binding().matches(it) shouldBe false }
    }

    @Test
    fun `field boundaries are part of the digest`() {
        val split = binding(what = action.copy(targets = listOf("4", "2")))
        val joined = binding(what = action.copy(targets = listOf("42")))

        split.matches(joined) shouldBe false
    }

    @Test
    fun `a pending confirmation confirms a matching second step before it expires`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))

        pending.check(binding(), now.plusSeconds(59)) shouldBe ConfirmationResult.Confirmed
    }

    @Test
    fun `a pending confirmation is expired from its expiry instant on`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))

        pending.check(binding(), now.plusSeconds(60)) shouldBe
            ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)
    }

    @Test
    fun `a pending confirmation refuses another session or target`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))
        val mismatch = ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)

        pending.check(binding(who = requester.copy(session = "session-b")), now) shouldBe mismatch
        pending.check(binding(what = action.copy(targets = listOf("43"))), now) shouldBe mismatch
    }

    @Test
    fun `tokens and sessions never show up in text`() {
        val token = ConfirmationToken("secret-token-value")
        val required = ConfirmationResult.Required(token, now, action)

        token.toString() shouldNotContain "secret-token-value"
        required.toString() shouldNotContain "secret-token-value"
        requester.toString() shouldNotContain "session-a"
    }

    @Test
    fun `invalid values are refused`() {
        shouldThrow<IllegalArgumentException> { ConfirmationToken(" ") }
        shouldThrow<IllegalArgumentException> { ConfirmationRequester(Actor.User, "") }
        shouldThrow<IllegalArgumentException> { ConfirmableAction("", listOf("42"), "") }
        shouldThrow<IllegalArgumentException> { ConfirmableAction("applications.delete", emptyList(), "") }
        shouldThrow<IllegalArgumentException> { ConfirmableAction("applications.delete", listOf(" "), "") }
    }
}
