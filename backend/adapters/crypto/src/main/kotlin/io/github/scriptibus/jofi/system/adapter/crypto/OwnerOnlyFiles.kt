// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Key material in the data volume: files readable by the Jofi user only (0600, directories 0700).
 * `app` and `worker` share the volume and may start together, so a file is written completely under
 * a temporary name and then hard-linked into place, which fails instead of replacing a file the
 * other process created first. Filesystems without POSIX permissions (some NAS shares) keep their
 * own access rules.
 */
internal object OwnerOnlyFiles {
    private val FILE = PosixFilePermissions.fromString("rw-------")
    private val DIRECTORY = PosixFilePermissions.fromString("rwx------")

    /** Writes [content] to [target] unless it exists; `true` when this call created it. */
    fun createIfAbsent(
        target: Path,
        content: ByteArray,
    ): Boolean {
        ensureDirectory(target.parent)
        if (Files.exists(target)) return false
        // Random, not the PID: in containers both processes are PID 1 and share the volume.
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        return try {
            Files
                .newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { it.write(content) }
            restrict(temporary)
            linkIntoPlace(target, temporary)
        } finally {
            removeTemporary(temporary)
        }
    }

    /** `false` when the other process linked its file first; the caller then reads that one. */
    private fun linkIntoPlace(
        target: Path,
        temporary: Path,
    ): Boolean =
        try {
            Files.createLink(target, temporary)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }

    private fun removeTemporary(temporary: Path) {
        Files.deleteIfExists(temporary)
    }

    /** Removes group and other access from [path] (e.g. a keyset copied in with a loose umask). */
    fun restrict(path: Path) {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java) ?: return
        val wanted = if (Files.isDirectory(path)) DIRECTORY else FILE
        if (view.readAttributes().permissions() != wanted) view.setPermissions(wanted)
    }

    // The directory is restricted before any file is written, so a new file is never reachable by
    // others, even for the moment before its own permissions are set.
    private fun ensureDirectory(directory: Path) {
        if (!Files.isDirectory(directory)) Files.createDirectories(directory)
        restrict(directory)
    }
}
