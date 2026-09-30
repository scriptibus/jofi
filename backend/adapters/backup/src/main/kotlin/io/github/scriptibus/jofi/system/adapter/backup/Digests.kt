// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat

/** Copies streams while counting bytes and computing SHA-256, the checksums of the manifest. */
internal object Digests {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Copies [input] to [output] and returns size and SHA-256 of what was copied. More than [limit]
     * bytes is refused as [BackupProblem.TOO_LARGE]: sizes in zip headers are not trusted.
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        limit: Long = Long.MAX_VALUE,
    ): EntryDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > limit) throw BackupRefusal(BackupProblem.TOO_LARGE)
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return EntryDigest(size, HexFormat.of().formatHex(digest.digest()))
    }
}

/** Refuses an upload once it grows beyond [limit] bytes, whatever it declared. */
internal class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = super.read(buffer, offset, length).also { if (it > 0) counted(it.toLong()) }

    private fun counted(bytes: Long) {
        count += bytes
        if (count > limit) throw BackupRefusal(BackupProblem.TOO_LARGE)
    }
}
