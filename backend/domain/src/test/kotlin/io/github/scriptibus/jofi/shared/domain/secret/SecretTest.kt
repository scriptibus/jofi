// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.secret

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

class SecretTest {
    private val clearText = "correct horse battery staple"

    @Test
    fun `a secret value reveals its text only on request`() {
        val secret = SecretValue(clearText)

        secret.reveal() shouldBe clearText
        secret.toString() shouldNotContain clearText
        "failed with $secret" shouldNotContain clearText
    }

    @Test
    fun `secret values compare by content and must not be blank`() {
        SecretValue(clearText) shouldBe SecretValue(clearText)
        SecretValue(clearText).hashCode() shouldBe SecretValue(clearText).hashCode()
        SecretValue(clearText) shouldNotBe SecretValue("other")
        shouldThrow<IllegalArgumentException> { SecretValue(" ") }
    }

    @Test
    fun `every secret-store outcome is a value that never carries the secret`() {
        val id = SecretId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
        val results: List<SecretResult<SecretValue>> =
            listOf(
                SecretResult.Success(SecretValue(clearText)),
                SecretResult.NotFound,
                SecretResult.Undecryptable,
                SecretResult.StorageFailure("get"),
            )

        val described =
            results.map {
                when (it) {
                    is SecretResult.Success -> "found"
                    SecretResult.NotFound -> "missing ${id.value}"
                    SecretResult.Undecryptable -> "undecryptable"
                    is SecretResult.StorageFailure -> it.operation
                }
            }

        described shouldBe listOf("found", "missing 00000000-0000-0000-0000-00000000000a", "undecryptable", "get")
        results.joinToString() shouldNotContain clearText
    }
}
