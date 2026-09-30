// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.EntryDigest
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

/**
 * Unpacks an uploaded archive into an empty directory, refusing it ([BackupRefusal]) as soon as it
 * breaks a rule (ADR-0042): upload, unpacked size and entry count within [limits] (counted on the
 * real bytes, not the sizes the zip claims), only paths of the format (zip slip), no entry twice.
 * Only regular files are created, so an entry can never become a link; directory entries create
 * nothing.
 */
internal class BackupZipReader(
    private val limits: BackupLimits,
) {
    /** Every file entry with its real size and SHA-256. */
    fun unpack(
        upload: InputStream,
        root: Path,
    ): MutableMap<BackupPath, EntryDigest> {
        val found = linkedMapOf<BackupPath, EntryDigest>()
        var unpacked = 0L
        ZipInputStream(LimitedInputStream(upload, limits.maxUploadBytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .onEachIndexed { index, _ -> refuseIf(index >= limits.maxEntries, BackupProblem.TOO_MANY_ENTRIES) }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val path = BackupPath.parse(entry.name) ?: throw BackupRefusal(BackupProblem.UNSAFE_PATH)
                    refuseIf(path in found, BackupProblem.DUPLICATE_ENTRY)
                    val digest = extract(zip, root, path, limitFor(path, limits.maxUnpackedBytes - unpacked))
                    unpacked += digest.size
                    found[path] = digest
                }
        }
        return found
    }

    private fun refuseIf(
        condition: Boolean,
        problem: BackupProblem,
    ) {
        if (condition) throw BackupRefusal(problem)
    }

    private fun limitFor(
        path: BackupPath,
        remaining: Long,
    ): Long =
        when (path) {
            BackupPath.KEYSET -> minOf(remaining, BackupLimits.MAX_KEYSET_BYTES)
            BackupPath.MANIFEST -> minOf(remaining, BackupLimits.MAX_MANIFEST_BYTES)
            else -> remaining
        }

    private fun extract(
        zip: InputStream,
        root: Path,
        path: BackupPath,
        limit: Long,
    ): EntryDigest {
        val target = root.resolve(path.value).normalize()
        // BackupPath already rules out escaping paths; this keeps it true whatever the path rules become.
        refuseIf(!target.startsWith(root) || target == root, BackupProblem.UNSAFE_PATH)
        Files.createDirectories(target.parent)
        return Files
            .newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            .use { Digests.copy(zip, it, limit) }
    }
}
