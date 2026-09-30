// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import io.github.scriptibus.jofi.system.application.port.PasswordHasherPort
import io.github.scriptibus.jofi.system.domain.Password
import io.github.scriptibus.jofi.system.domain.PasswordHash
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

/**
 * argon2id password hashing (ADR-0017) with the OWASP Password Storage Cheat Sheet parameters
 * m = 19 MiB, t = 2, p = 1, a 16-byte salt and a 32-byte hash. The parameters are encoded in each
 * hash, so raising them later still verifies older hashes.
 *
 * Each hash needs 19 MiB for a moment. At most [CONCURRENT_HASHES] run at once, so a burst of
 * login requests cannot exhaust the memory of a small home server.
 */
@Component
class Argon2PasswordHasherAdapter : PasswordHasherPort {
    private val encoder = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)
    private val permits = Semaphore(CONCURRENT_HASHES, true)

    override fun hash(password: Password): PasswordHash =
        limited {
            PasswordHash(requireNotNull(encoder.encode(password.reveal())))
        }

    override fun matches(
        password: Password,
        hash: PasswordHash,
    ): Boolean = limited { encoder.matches(password.reveal(), hash.encoded) }

    private fun <T> limited(work: () -> T): T {
        permits.acquireUninterruptibly()
        try {
            return work()
        } finally {
            permits.release()
        }
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val PARALLELISM = 1
        const val MEMORY_KIB = 19_456
        const val ITERATIONS = 2
        const val CONCURRENT_HASHES = 2
    }
}
