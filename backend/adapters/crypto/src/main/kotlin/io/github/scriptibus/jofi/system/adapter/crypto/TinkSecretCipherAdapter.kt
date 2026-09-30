// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.github.scriptibus.jofi.shared.application.port.SecretCipherPort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException

/**
 * Encrypts secrets with Tink AES-256-GCM (ADR-0017) under the master keyset in the data volume
 * (`<data-dir>/secrets/master-keyset.json`, owner-only). The keyset is generated on the first start
 * and loaded once; the database never sees it, so a database dump alone reveals no secret. Each
 * ciphertext is bound to its secret id as associated data. Nothing here logs key material,
 * plaintext or ciphertext.
 *
 * Loading happens in an [ApplicationRunner], after the context refresh: startup fails fast on a
 * broken keyset, and the image build's AOT training run (which exits on refresh) never writes a key.
 */
@Component
class TinkSecretCipherAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: Path,
) : SecretCipherPort,
    ApplicationRunner {
    private val keysetFile: Path = dataDirectory.resolve(SECRETS_DIRECTORY).resolve(KEYSET_FILE)
    private val aead: Aead by lazy { loadOrCreateKeyset() }

    override fun run(args: ApplicationArguments) {
        aead
    }

    override fun encrypt(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<ByteArray> {
        val primitive = keyset() ?: return SecretResult.StorageFailure(ENCRYPT)
        return try {
            SecretResult.Success(primitive.encrypt(value.reveal().toByteArray(Charsets.UTF_8), associatedData(id)))
        } catch (exception: GeneralSecurityException) {
            failure(ENCRYPT, exception)
        }
    }

    override fun decrypt(
        id: SecretId,
        ciphertext: ByteArray,
    ): SecretResult<SecretValue> {
        val primitive = keyset() ?: return SecretResult.StorageFailure(DECRYPT)
        return try {
            SecretResult.Success(SecretValue(String(primitive.decrypt(ciphertext, associatedData(id)), Charsets.UTF_8)))
        } catch (_: GeneralSecurityException) {
            // Tampered, moved to another id, or encrypted under a different keyset.
            SecretResult.Undecryptable
        } catch (_: IllegalArgumentException) {
            // Decrypted to a blank value, which the store never writes.
            SecretResult.Undecryptable
        }
    }

    // The keyset loads once; a failure (unreadable file, broken JSON) is retried on the next call.
    // Tink and file access throw checked and unchecked exceptions alike, hence the broad catch.
    private fun keyset(): Aead? =
        try {
            aead
        } catch (exception: Exception) {
            failure<Unit>("load keyset", exception)
            null
        }

    private fun loadOrCreateKeyset(): Aead {
        AeadConfig.register()
        if (!Files.exists(keysetFile)) {
            val generated = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            val json = TinkJsonProtoKeysetFormat.serializeKeyset(generated, InsecureSecretKeyAccess.get())
            if (OwnerOnlyFiles.createIfAbsent(keysetFile, json.toByteArray(Charsets.UTF_8))) {
                logger.info(
                    "Generated a new master keyset at {}; back up the data volume with the database",
                    keysetFile,
                )
            }
        }
        OwnerOnlyFiles.restrict(keysetFile)
        val handle = TinkJsonProtoKeysetFormat.parseKeyset(Files.readString(keysetFile), InsecureSecretKeyAccess.get())
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    // Only the operation and the exception type: messages could describe key material.
    private fun <T> failure(
        operation: String,
        exception: Exception,
    ): SecretResult<T> {
        logger.error("Secret {} failed: {}", operation, exception.javaClass.name)
        return SecretResult.StorageFailure(operation)
    }

    private fun associatedData(id: SecretId): ByteArray =
        "$ASSOCIATED_DATA_PREFIX${id.value}".toByteArray(Charsets.UTF_8)

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TinkSecretCipherAdapter::class.java)
        const val ENCRYPT = "encrypt"
        const val DECRYPT = "decrypt"
        const val SECRETS_DIRECTORY = "secrets"
        const val KEYSET_FILE = "master-keyset.json"

        /** Domain separation: the id of a row in the `secret` table, not of anything else. */
        const val ASSOCIATED_DATA_PREFIX = "jofi:secret:"
    }
}
