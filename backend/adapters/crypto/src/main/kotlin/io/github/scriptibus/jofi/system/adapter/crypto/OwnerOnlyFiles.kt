// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Key material in the data volume: files readable by the Jofi user only (0600, directories 0700).
 * `app` and `worker` share the volume and may start together, so a file is written completely under
 * a temporary name and then hard-linked into place, which fails instead of replacing a file the
 * other process created first. The data volume must therefore support hard links (local disks and
 * Docker/Podman volumes do; some NAS/SMB mounts do not, see [DataVolumeException]). Filesystems
 * without POSIX permissions keep their own access rules.
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

    /**
     * Replaces [target] with [content] in one atomic rename, so a reader sees the old or the new file,
     * never a partial one (a restored keyset, ADR-0042).
     */
    fun replace(
        target: Path,
        content: ByteArray,
    ) {
        ensureDirectory(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files
                .newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { it.write(content) }
            restrict(temporary)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
        } catch (_: UnsupportedOperationException) {
            throw DataVolumeException(target.parent)
        } catch (exception: FileSystemException) {
            if (Files.exists(target)) false else throw DataVolumeException(target.parent, exception)
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

/**
 * The data volume cannot hold key material safely because it does not support hard links. The
 * message only names the directory, so it is safe to log.
 */
internal class DataVolumeException(
    directory: Path,
    cause: Throwable? = null,
) : IOException(
        "The data volume at $directory does not support hard links, which Jofi needs to write key files " +
            "atomically. Use a local disk or a Docker/Podman volume for JOFI_DATA_DIR, not an SMB/CIFS share.",
        cause,
    )

/** Resolves `jofi.data-dir`: it must be an absolute path, so a working directory never decides where keys go. */
internal object DataDirectory {
    fun of(value: String): Path {
        val path = Path.of(value.trim())
        require(value.isNotBlank() && path.isAbsolute) {
            "JOFI_DATA_DIR must be the absolute path of the data volume (e.g. /data in the container), not '$value'"
        }
        return path
    }
}
