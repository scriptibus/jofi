// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.system.application.port.PasswordResetMarkerPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The marker `<data-dir>/secrets/password-reset-applied`: the current `JOFI_RESET_PASSWORD` was applied. */
@Component
class PasswordResetMarkerFileAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: String,
) : PasswordResetMarkerPort {
    private val markerFile: Path = DataDirectory.of(dataDirectory).resolve(SECRETS_DIRECTORY).resolve(MARKER_FILE)

    override fun isSet(): Boolean = Files.exists(markerFile)

    override fun set(): AuthSideEffectResult =
        guarded("set") { OwnerOnlyFiles.createIfAbsent(markerFile, ByteArray(0)) }

    override fun clear(): AuthSideEffectResult = guarded("clear") { Files.deleteIfExists(markerFile) }

    private fun guarded(
        operation: String,
        work: () -> Unit,
    ): AuthSideEffectResult =
        try {
            work()
            AuthSideEffectResult.Success
        } catch (exception: DataVolumeException) {
            logger.error(exception.message)
            AuthSideEffectResult.Failure
        } catch (exception: IOException) {
            logger.error("Password reset marker {} failed: {}", operation, exception.javaClass.name)
            AuthSideEffectResult.Failure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PasswordResetMarkerFileAdapter::class.java)
        const val SECRETS_DIRECTORY = "secrets"
        const val MARKER_FILE = "password-reset-applied"
    }
}
