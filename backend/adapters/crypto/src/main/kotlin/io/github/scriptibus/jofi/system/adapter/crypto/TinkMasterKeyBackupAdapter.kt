// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationResult
import io.github.scriptibus.jofi.system.application.port.MasterKeyBackupPort
import io.github.scriptibus.jofi.system.domain.SystemStoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupRestore
import io.github.scriptibus.jofi.system.domain.backup.MasterKeysetCopy
import io.github.scriptibus.jofi.system.domain.backup.StagedBackup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies the master keyset into a backup and puts a backup's keyset in place on restore (ADR-0042).
 * The keyset is written owner-only in one atomic rename; `TinkSecretCipherAdapter` (in `app` and in
 * `worker`) notices the new file and loads it. Nothing here logs key material.
 */
@Component
class TinkMasterKeyBackupAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: String,
) : MasterKeyBackupPort {
    private val keysetFile: Path = MasterKeysetFile.of(dataDirectory)

    init {
        AeadConfig.register()
    }

    override fun copy(): SystemStoreResult<MasterKeysetCopy?> =
        try {
            SystemStoreResult.Success(
                if (Files.exists(keysetFile)) MasterKeysetCopy(Files.readAllBytes(keysetFile)) else null,
            )
        } catch (exception: IOException) {
            logger.error("Copying the master keyset failed: {}", exception.javaClass.name)
            SystemStoreResult.StorageFailure("copy keyset")
        }

    override fun verifies(
        keyset: MasterKeysetCopy,
        checkValue: ByteArray,
    ): Boolean = primitive(keyset)?.let { MasterKeysetFile.verifies(it, checkValue) } ?: false

    override fun reinstate(previous: MasterKeysetCopy): Boolean =
        replace(previous, "the previous master keyset of an undone restore")

    override fun install(
        backup: StagedBackup,
        proof: ConfirmationResult.Confirmed,
    ): Boolean {
        val keyset = backup.keyset
        if (keyset == null || !BackupRestore.confirms(proof, backup)) return false
        return replace(keyset, "the master keyset of the restored backup")
    }

    private fun replace(
        keyset: MasterKeysetCopy,
        what: String,
    ): Boolean {
        if (primitive(keyset) == null) return false
        return try {
            OwnerOnlyFiles.replace(keysetFile, keyset.bytes())
            logger.warn("Installed {} at {}", what, keysetFile)
            true
        } catch (exception: IOException) {
            logger.error("Installing {} failed: {}", what, exception.javaClass.name)
            false
        }
    }

    // Tink throws checked and unchecked exceptions for a broken keyset, hence the broad catch.
    private fun primitive(keyset: MasterKeysetCopy): Aead? =
        try {
            MasterKeysetFile.primitive(String(keyset.bytes(), Charsets.UTF_8))
        } catch (exception: Exception) {
            logger.warn("A backup's master keyset is not a readable keyset: {}", exception.javaClass.name)
            null
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(TinkMasterKeyBackupAdapter::class.java)
    }
}
