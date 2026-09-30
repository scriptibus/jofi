// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordHash
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class Argon2PasswordHasherAdapterTest {
    private val hasher = Argon2PasswordHasherAdapter()
    private val password = requireNotNull(Password.submitted("correct horse battery staple"))

    @Test
    fun `hashes with argon2id and the OWASP parameters`() {
        val hash = hasher.hash(password)

        hash.encoded shouldStartWith "\$argon2id\$v=19\$m=19456,t=2,p=1\$"
        hash.encoded shouldNotContain password.reveal()
    }

    @Test
    fun `every hash has its own salt and verifies only the right password`() {
        val first = hasher.hash(password)

        hasher.hash(password) shouldNotBe first
        hasher.matches(password, first) shouldBe true
        hasher.matches(requireNotNull(Password.submitted("correct horse battery stapler")), first) shouldBe false
    }

    @Test
    fun `a malformed hash never matches and does not throw`() {
        hasher.matches(password, PasswordHash("not-a-hash")) shouldBe false
    }
}
