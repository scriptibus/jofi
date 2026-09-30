// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import io.github.scriptibus.jofi.shared.application.port.SecretCipherPort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import io.github.scriptibus.jofi.system.application.port.MasterKeyPort
import io.github.scriptibus.jofi.system.domain.MasterKeyState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException

/**
 * Encrypts secrets with Tink AES-256-GCM (ADR-0017, ADR-0035) under the master keyset in the data
 * volume (`<data-dir>/secrets/master-keyset.json`, owner-only). The database never sees the keyset,
 * so a database dump alone reveals no secret. Each ciphertext is bound to its secret id as
 * associated data. Nothing here logs key material, plaintext or ciphertext.
 *
 * A keyset is only created through [generate], which `VerifyMasterKeyUseCase` calls at startup after
 * checking the database: a missing file never silently turns into a new key. Encryption without a
 * keyset fails instead.
 */
@Component
class TinkSecretCipherAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: String,
) : SecretCipherPort,
    MasterKeyPort {
    private val keysetFile: Path = MasterKeysetFile.of(dataDirectory)

    @Volatile
    private var loaded: Aead? = null

    // Identifies the file [loaded] came from: a restore (possibly by the other container) replaces it.
    private var loadedVersion: List<Any?>? = null

    init {
        AeadConfig.register()
    }

    override fun state(): MasterKeyState =
        when {
            !Files.exists(keysetFile) -> MasterKeyState.MISSING
            keyset() == null -> MasterKeyState.UNREADABLE
            else -> MasterKeyState.PRESENT
        }

    @Synchronized
    override fun generate(): MasterKeyState {
        if (!Files.exists(keysetFile)) {
            try {
                val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
                val json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
                if (OwnerOnlyFiles.createIfAbsent(keysetFile, json.toByteArray(Charsets.UTF_8))) {
                    logger.warn("Generated a new master keyset at {}; back it up with the database", keysetFile)
                }
            } catch (exception: DataVolumeException) {
                // Explains itself and names only the directory.
                logger.error(exception.message)
            } catch (exception: IOException) {
                failure<Unit>("write keyset", exception)
            } catch (exception: GeneralSecurityException) {
                failure<Unit>("generate keyset", exception)
            }
        }
        loaded = null
        return state()
    }

    override fun newCheckValue(): ByteArray? {
        val primitive = keyset() ?: return null
        return try {
            MasterKeysetFile.newCheckValue(primitive)
        } catch (exception: GeneralSecurityException) {
            failure<Unit>(ENCRYPT, exception)
            null
        }
    }

    override fun verifies(checkValue: ByteArray): Boolean =
        keyset()?.let { MasterKeysetFile.verifies(it, checkValue) } ?: false

    override fun encrypt(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<ByteArray> = encryptBytes(value.reveal().toByteArray(Charsets.UTF_8), associatedData(id))

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

    private fun encryptBytes(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): SecretResult<ByteArray> {
        val primitive = keyset() ?: return SecretResult.StorageFailure(ENCRYPT)
        return try {
            SecretResult.Success(primitive.encrypt(plaintext, associatedData))
        } catch (exception: GeneralSecurityException) {
            failure(ENCRYPT, exception)
        }
    }

    // Loads the keyset once per file: a missing file stays missing (null), a broken one is logged and
    // retried, and a replaced one (restore, ADR-0042) is loaded again. Tink and file access throw checked
    // and unchecked exceptions alike, hence the broad catch.
    @Synchronized
    private fun keyset(): Aead? {
        val version = MasterKeysetFile.version(keysetFile)
        if (version == null) {
            loaded = null
        } else if (loaded == null || version != loadedVersion) {
            loaded = load()
            loadedVersion = version
        }
        return loaded
    }

    private fun load(): Aead? =
        try {
            OwnerOnlyFiles.restrict(keysetFile)
            MasterKeysetFile.primitive(Files.readString(keysetFile))
        } catch (exception: Exception) {
            failure<Unit>("load keyset", exception)
            null
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

        /** Domain separation: the id of a row in the `secret` table, not of anything else. */
        const val ASSOCIATED_DATA_PREFIX = "jofi:secret:"
    }
}
