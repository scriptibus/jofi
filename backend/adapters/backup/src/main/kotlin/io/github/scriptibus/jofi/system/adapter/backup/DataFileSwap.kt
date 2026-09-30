// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Swaps the data volume's directories (knowledge, documents) for a backup's by renaming: the live
 * directory moves into the work directory (`previous/<root>`), the backup's (`files/<root>`) into its
 * place. Renames within one filesystem are atomic. Before a root is touched, `swap-<root>` records
 * whether the backup has it, so [revert] can tell every state apart, also after a crash in the middle
 * of a swap, and runs the moves backwards.
 */
internal class DataFileSwap(
    private val dataDirectory: Path,
) {
    /** Swaps every root; a failure leaves the markers for [revert]. */
    fun install(work: Path) {
        BackupPath.DATA_ROOTS.forEach { swap(work, it) }
    }

    /** Undoes whatever [install] did, root by root; roots it never touched stay as they are. */
    fun revert(work: Path) {
        BackupPath.DATA_ROOTS.asReversed().forEach { unswap(work, it) }
    }

    private fun swap(
        work: Path,
        root: String,
    ) {
        val paths = RootPaths(dataDirectory, work, root)
        Files.createDirectories(paths.previous.parent)
        // An empty leftover of an earlier attempt that was undone; anything else must be recovered first.
        Files.deleteIfExists(paths.previous)
        Files.writeString(paths.marker, if (exists(paths.incoming)) INCOMING else NONE)
        if (exists(paths.live)) move(paths.live, paths.previous)
        if (exists(paths.incoming)) move(paths.incoming, paths.live)
    }

    private fun unswap(
        work: Path,
        root: String,
    ) {
        val paths = RootPaths(dataDirectory, work, root)
        if (!exists(paths.marker)) return
        val movedIn = Files.readString(paths.marker) == INCOMING && !exists(paths.incoming)
        if (movedIn && exists(paths.live)) move(paths.live, paths.incoming)
        if (exists(paths.previous)) move(paths.previous, paths.live)
        Files.delete(paths.marker)
    }

    private fun exists(path: Path) = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun move(
        from: Path,
        to: Path,
    ) {
        if (exists(to)) throw IOException("Cannot move a backup directory onto an existing one")
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Where one root lives, in the data volume and in the work directory. */
    private class RootPaths(
        dataDirectory: Path,
        work: Path,
        root: String,
    ) {
        val live: Path = dataDirectory.resolve(root)
        val previous: Path = work.resolve(PREVIOUS).resolve(root)
        val incoming: Path = work.resolve(FILES).resolve(root)
        val marker: Path = work.resolve("swap-$root")
    }

    companion object {
        const val FILES = "files"
        const val PREVIOUS = "previous"
        private const val INCOMING = "incoming"
        private const val NONE = "none"
    }
}
