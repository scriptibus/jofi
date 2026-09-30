// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.AuthSideEffectResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The one-time setup token in `<data-dir>/secrets/setup-token` (owner-only). Required while Jofi is
 * bound to a non-loopback address ([bindAddress], from `JOFI_BIND_ADDRESS`; ADR-0029): then only
 * someone who can read the data volume can choose the first password. The token itself is never
 * logged, only where to find it.
 */
@Component
class SetupTokenFileAdapter(
    @Value("\${jofi.data-dir}") dataDirectory: Path,
    @Value("\${jofi.auth.bind-address}") private val bindAddress: String,
) : SetupTokenPort {
    private val tokenFile: Path = dataDirectory.resolve(SECRETS_DIRECTORY).resolve(TOKEN_FILE)
    private val random = SecureRandom()

    override fun isRequired(): Boolean = !isLoopback(bindAddress.trim().removePrefix("[").removeSuffix("]"))

    override fun issue(): AuthSideEffectResult =
        guarded("issue") {
            OwnerOnlyFiles.createIfAbsent(tokenFile, newToken().toByteArray(Charsets.US_ASCII))
            logger.warn(
                "Jofi is exposed on {} and no password is set: first run needs the setup token in {}",
                bindAddress,
                tokenFile,
            )
        }

    override fun matches(candidate: String): Boolean {
        val issued = readIssued()
        val given = candidate.trim().toByteArray(Charsets.US_ASCII)
        return issued != null && issued.isNotEmpty() && MessageDigest.isEqual(issued, given)
    }

    private fun readIssued(): ByteArray? =
        try {
            Files.readAllBytes(tokenFile)
        } catch (_: NoSuchFileException) {
            null
        } catch (exception: IOException) {
            logger.error("Reading the setup token failed: {}", exception.javaClass.name)
            null
        }

    override fun discard(): AuthSideEffectResult = guarded("discard") { Files.deleteIfExists(tokenFile) }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun guarded(
        operation: String,
        work: () -> Unit,
    ): AuthSideEffectResult =
        try {
            work()
            AuthSideEffectResult.Success
        } catch (exception: IOException) {
            logger.error("Setup token {} failed: {}", operation, exception.javaClass.name)
            AuthSideEffectResult.Failure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SetupTokenFileAdapter::class.java)
        const val SECRETS_DIRECTORY = "secrets"
        const val TOKEN_FILE = "setup-token"
        const val TOKEN_BYTES = 32

        /** Literal addresses only: a host name other than `localhost` counts as exposed (no DNS lookup). */
        fun isLoopback(address: String): Boolean =
            address.equals("localhost", ignoreCase = true) ||
                try {
                    InetAddress.ofLiteral(address).isLoopbackAddress
                } catch (_: IllegalArgumentException) {
                    false
                }
    }
}
