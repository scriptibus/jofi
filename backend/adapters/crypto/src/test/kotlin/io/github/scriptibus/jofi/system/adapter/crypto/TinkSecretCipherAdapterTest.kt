// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class TinkSecretCipherAdapterTest {
    @TempDir
    lateinit var dataDirectory: Path

    private val id = SecretId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val other = SecretId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    private val secret = SecretValue("sk-test-api-key-4711")

    private fun adapter() = TinkSecretCipherAdapter(dataDirectory).also { it.run(DefaultApplicationArguments()) }

    private fun keysetFile() = dataDirectory.resolve("secrets/master-keyset.json")

    private fun encrypt(
        adapter: TinkSecretCipherAdapter,
        secretId: SecretId = id,
    ): ByteArray = (adapter.encrypt(secretId, secret) as SecretResult.Success).value

    @Test
    fun `a secret round-trips and the ciphertext does not contain it`() {
        val adapter = adapter()
        val ciphertext = encrypt(adapter)

        String(ciphertext, Charsets.ISO_8859_1).contains(secret.reveal()) shouldBe false
        adapter.decrypt(id, ciphertext) shouldBe SecretResult.Success(secret)
        // AES-GCM uses a fresh nonce each time.
        encrypt(adapter) shouldNotBe ciphertext
    }

    @Test
    fun `a ciphertext moved to another secret id does not decrypt`() {
        val adapter = adapter()

        adapter.decrypt(other, encrypt(adapter)) shouldBe SecretResult.Undecryptable
    }

    @Test
    fun `a tampered ciphertext does not decrypt`() {
        val adapter = adapter()
        val ciphertext = encrypt(adapter)

        ciphertext.indices.forEach { index ->
            val tampered = ciphertext.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            adapter.decrypt(id, tampered) shouldBe SecretResult.Undecryptable
        }
        adapter.decrypt(id, ciphertext.copyOf(ciphertext.size - 1)) shouldBe SecretResult.Undecryptable
    }

    @Test
    fun `the keyset is generated once, owner-only, and reused after a restart`() {
        val ciphertext = encrypt(adapter())

        PosixFilePermissions.toString(Files.getPosixFilePermissions(keysetFile())) shouldBe "rw-------"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(keysetFile().parent)) shouldBe "rwx------"
        val keyset = Files.readString(keysetFile())

        val restarted = adapter()
        Files.readString(keysetFile()) shouldBe keyset
        restarted.decrypt(id, ciphertext) shouldBe SecretResult.Success(secret)
    }

    @Test
    fun `loose permissions on an existing keyset are tightened`() {
        adapter()
        Files.setPosixFilePermissions(keysetFile(), PosixFilePermissions.fromString("rw-r--r--"))

        adapter()

        PosixFilePermissions.toString(Files.getPosixFilePermissions(keysetFile())) shouldBe "rw-------"
    }

    @Test
    fun `a ciphertext of another keyset does not decrypt`(
        @TempDir otherDataDirectory: Path,
    ) {
        val foreign = TinkSecretCipherAdapter(otherDataDirectory)

        adapter().decrypt(id, encrypt(foreign)) shouldBe SecretResult.Undecryptable
    }

    @Test
    fun `an unreadable keyset is a storage failure, not an exception`() {
        Files.createDirectories(keysetFile().parent)
        Files.writeString(keysetFile(), "not a keyset")
        val adapter = TinkSecretCipherAdapter(dataDirectory)

        adapter.encrypt(id, secret).shouldBeInstanceOf<SecretResult.StorageFailure>()
        adapter.decrypt(id, byteArrayOf(1, 2, 3)).shouldBeInstanceOf<SecretResult.StorageFailure>()
    }
}
