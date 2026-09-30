// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PasswordTest {
    private val clearText = "correct horse battery staple"

    @Test
    fun `a chosen password needs at least 15 characters, counted as code points`() {
        Password.chosen("a".repeat(14)) shouldBe PasswordPolicyCheck.TooShort(15)
        // Seven emoji are 14 UTF-16 chars but only 7 characters.
        Password.chosen("🔑".repeat(7)) shouldBe PasswordPolicyCheck.TooShort(15)
        Password.chosen("a".repeat(15)).shouldBeInstanceOf<PasswordPolicyCheck.Accepted>()
    }

    @Test
    fun `a chosen password may have at most 256 characters`() {
        Password.chosen("a".repeat(257)) shouldBe PasswordPolicyCheck.TooLong(256)
        Password.chosen("a".repeat(256)).shouldBeInstanceOf<PasswordPolicyCheck.Accepted>()
    }

    @Test
    fun `a submitted password is refused when empty or longer than any valid one`() {
        Password.submitted("").shouldBeNull()
        Password.submitted("a".repeat(257)).shouldBeNull()
        Password.submitted("short")?.reveal() shouldBe "short"
    }

    @Test
    fun `passwords and hashes never print their value`() {
        val password = (Password.chosen(clearText) as PasswordPolicyCheck.Accepted).password
        val hash = PasswordHash("\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA")

        password.reveal() shouldBe clearText
        "$password $hash ${PasswordPolicyCheck.Accepted(password)}" shouldNotContain clearText
        hash.toString() shouldNotContain "argon2id"
        password shouldBe Password.submitted(clearText)
        password.hashCode() shouldBe Password.submitted(clearText).hashCode()
        hash shouldBe PasswordHash(hash.encoded)
        hash.hashCode() shouldBe PasswordHash(hash.encoded).hashCode()
        hash shouldNotBe PasswordHash("other")
        shouldThrow<IllegalArgumentException> { PasswordHash(" ") }
    }

    @Test
    fun `a password change request and a session id never print their values`() {
        val request =
            PasswordChangeRequest(clearText, "new $clearText", SessionRef("session-1"), ThrottleKey.Client("10.0.0.1"))

        "$request ${request.session} ${request.client}" shouldNotContain clearText
        "$request ${request.session} ${request.client}" shouldNotContain "session-1"
        request.client.toString() shouldNotContain "10.0.0.1"
        SessionRef("a") shouldBe SessionRef("a")
        SessionRef("a").hashCode() shouldBe SessionRef("a").hashCode()
        shouldThrow<IllegalArgumentException> { SessionRef("") }
    }

    @Test
    fun `an account keeps its creation time when the password changes`() {
        val created = Instant.parse("2026-09-30T10:00:00Z")
        val account = UserAccount(AccountId(UUID(0, 1)), PasswordHash("old"), created, created)
        val changed = account.withPassword(PasswordHash("new"), created.plusSeconds(60))

        changed.createdAt shouldBe created
        changed.passwordChangedAt shouldBe created.plusSeconds(60)
        changed.passwordHash shouldBe PasswordHash("new")
        shouldThrow<IllegalArgumentException> {
            UserAccount(AccountId(UUID(0, 1)), PasswordHash("x"), created, created.minusSeconds(1))
        }
    }
}
