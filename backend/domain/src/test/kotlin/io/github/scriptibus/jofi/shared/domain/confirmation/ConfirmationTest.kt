// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.confirmation

import io.github.scriptibus.jofi.shared.domain.Actor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfirmationTest {
    private val requester = ConfirmationRequester(Actor.User, "session-a")
    private val effect = ConfirmationEffect("application", "ACME", mapOf("documents" to 3))
    private val action = action()
    private val now = Instant.parse("2026-09-30T10:00:00Z")

    private fun action(
        operation: String = "applications.delete",
        targets: List<String> = listOf("42"),
        effect: ConfirmationEffect = this.effect,
    ) = ConfirmableAction(operation, targets, effect)

    private fun binding(
        who: ConfirmationRequester = requester,
        what: ConfirmableAction = action,
    ) = ConfirmationBinding.of(who, what)

    @Test
    fun `the same requester and action bind to the same digest`() {
        binding().matches(binding()) shouldBe true
    }

    @Test
    fun `targets are sorted and de-duplicated, so the same set binds the same way`() {
        val messy = action(targets = listOf("43", "42", "43"))

        messy.targets shouldBe listOf("42", "43")
        messy shouldBe action(targets = listOf("42", "43"))
        binding(what = messy).matches(binding(what = action(targets = listOf("42", "43")))) shouldBe true
    }

    @Test
    fun `any other actor, session, operation, target or effect is a different binding`() {
        val others =
            listOf(
                binding(who = requester.copy(session = "session-b")),
                binding(who = requester.copy(actor = Actor.ExternalClient("Claude Desktop"))),
                binding(who = requester.copy(actor = Actor.Ai)),
                binding(what = action(operation = "applications.archive")),
                binding(what = action(targets = listOf("43"))),
                binding(what = action(targets = listOf("42", "43"))),
                binding(what = action(effect = effect.copy(kind = "company"))),
                binding(what = action(effect = effect.copy(name = "ACME GmbH"))),
                binding(what = action(effect = effect.copy(counts = mapOf("documents" to 4)))),
                binding(what = action(effect = effect.copy(counts = mapOf("notes" to 3)))),
            )

        others.forEach { binding().matches(it) shouldBe false }
    }

    @Test
    fun `field boundaries are part of the digest`() {
        binding(
            what = action(targets = listOf("4", "2")),
        ).matches(binding(what = action(targets = listOf("42")))) shouldBe
            false
        binding(what = action(effect = ConfirmationEffect("ab", "c"))).matches(
            binding(what = action(effect = ConfirmationEffect("a", "bc"))),
        ) shouldBe false
    }

    @Test
    fun `a pending confirmation confirms a matching second step before it expires, carrying the action`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))

        val confirmed =
            pending
                .check(
                    requester,
                    action,
                    now.plusSeconds(59),
                ).shouldBeInstanceOf<ConfirmationResult.Confirmed>()

        confirmed.action shouldBe action
        confirmed.covers("applications.delete", "42") shouldBe true
        confirmed.covers("applications.delete", "43") shouldBe false
        confirmed.covers("applications.archive", "42") shouldBe false
    }

    @Test
    fun `a pending confirmation is expired from its expiry instant on`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))

        pending.check(requester, action, now.plusSeconds(60)) shouldBe
            ConfirmationResult.Rejected(ConfirmationRejection.EXPIRED)
    }

    @Test
    fun `a pending confirmation refuses another session or target`() {
        val pending = PendingConfirmation(binding(), now.plusSeconds(60))
        val mismatch = ConfirmationResult.Rejected(ConfirmationRejection.MISMATCH)

        pending.check(requester.copy(session = "session-b"), action, now) shouldBe mismatch
        pending.check(requester, action(targets = listOf("43")), now) shouldBe mismatch
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
        shouldThrow<IllegalArgumentException> { action(operation = "") }
        shouldThrow<IllegalArgumentException> { action(targets = emptyList()) }
        shouldThrow<IllegalArgumentException> { action(targets = listOf(" ")) }
        shouldThrow<IllegalArgumentException> { ConfirmationEffect("", "ACME") }
        shouldThrow<IllegalArgumentException> { ConfirmationEffect("application", "ACME", mapOf("documents" to -1)) }
        shouldThrow<IllegalArgumentException> { ConfirmationEffect("application", "ACME", mapOf(" " to 1)) }
    }
}
