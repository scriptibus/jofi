// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.name

/**
 * Work directories of exports and restores in the data volume (`<data>/backup-work/<id>`), on the
 * same filesystem as the data they replace, so restored files move into place by renaming. A backup
 * holds everything, so the directories are owner-only (0700). Leftovers of a crash are removed after
 * [STALE_AFTER], except a restore in progress ([RESTORE_MARKER]): recovery decides about that one.
 */
internal class WorkDirectories(
    dataDirectory: Path,
    private val clock: Clock,
) {
    private val root = dataDirectory.resolve(WORK_DIRECTORY)

    fun create(): BackupWorkspace {
        Files.createDirectories(root)
        ownerOnly(root)
        removeStale()
        val id = BackupId(UUID.randomUUID())
        val directory = Files.createDirectory(root.resolve(id.toString()))
        ownerOnly(directory)
        return BackupWorkspace(id, Files.createDirectory(directory.resolve(DATABASE_DIRECTORY)))
    }

    fun of(id: BackupId): Path = root.resolve(id.toString())

    /** The work directories marked as a restore in progress. */
    fun marked(): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { children ->
            children.filter { UUID_NAME.matches(it.name) && Files.exists(it.resolve(RESTORE_MARKER)) }.toList()
        }
    }

    /** Writes [content] to [file], readable by the owner only (a keyset copy). */
    fun writeOwnerOnly(
        file: Path,
        content: ByteArray,
    ) {
        Files.createDirectories(file.parent)
        Files.deleteIfExists(file)
        if (Files.getFileAttributeView(file.parent, PosixFileAttributeView::class.java) != null) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        Files.write(file, content)
    }

    /** Deletes [directory] and everything below it, without following links. */
    fun delete(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun removeStale() {
        val limit = FileTime.from(clock.instant().minus(STALE_AFTER))
        Files.list(root).use { children ->
            children
                .filter { UUID_NAME.matches(it.name) && Files.getLastModifiedTime(it).compareTo(limit) < 0 }
                .filter { !Files.exists(it.resolve(RESTORE_MARKER)) }
                .forEach(::delete)
        }
    }

    private fun ownerOnly(directory: Path) {
        val view = Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) ?: return
        view.setPermissions(PosixFilePermissions.fromString("rwx------"))
    }

    companion object {
        const val WORK_DIRECTORY = "backup-work"
        const val DATABASE_DIRECTORY = "database"

        /** Marks a restore that began replacing files or the keyset and has not ended. */
        const val RESTORE_MARKER = "restore-in-progress"

        /** The keyset that was in place before the restore, owner-only. */
        const val PREVIOUS_KEYSET = "previous/master-keyset.json"
        val STALE_AFTER: Duration = Duration.ofDays(1)
        private val UUID_NAME = Regex("[0-9a-f-]{36}")
    }
}
