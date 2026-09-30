// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SetupTokenFileAdapterTest {
    @TempDir
    lateinit var dataDirectory: Path

    private fun tokenFile() = dataDirectory.resolve("secrets/setup-token")

    private fun adapter(bindAddress: String = "0.0.0.0") = SetupTokenFileAdapter(dataDirectory, bindAddress)

    @Test
    fun `the token is required only when Jofi is bound beyond loopback`() {
        listOf("127.0.0.1", "127.0.0.2", "::1", "[::1]", "localhost", " LOCALHOST ").forEach {
            adapter(it).isRequired() shouldBe false
        }
        listOf("0.0.0.0", "::", "192.168.1.20", "fd00::1", "jofi.example.org").forEach {
            adapter(it).isRequired() shouldBe true
        }
    }

    @Test
    fun `issuing writes a random owner-only token once and keeps it`() {
        val adapter = adapter()

        adapter.issue() shouldBe AuthSideEffectResult.Success
        val token = Files.readString(tokenFile())
        adapter.issue() shouldBe AuthSideEffectResult.Success

        Files.readString(tokenFile()) shouldBe token
        token shouldMatch Regex("[A-Za-z0-9_-]{43}")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile())) shouldBe "rw-------"
    }

    @Test
    fun `only the issued token matches, and none after it is discarded`() {
        val adapter = adapter()
        adapter.matches("anything") shouldBe false
        adapter.issue()
        val token = Files.readString(tokenFile())

        adapter.matches(token) shouldBe true
        adapter.matches(" $token\n") shouldBe true
        adapter.matches(token.dropLast(1)) shouldBe false
        adapter.matches("") shouldBe false

        adapter.discard() shouldBe AuthSideEffectResult.Success
        Files.exists(tokenFile()) shouldBe false
        adapter.matches(token) shouldBe false
        adapter.discard() shouldBe AuthSideEffectResult.Success
    }
}
