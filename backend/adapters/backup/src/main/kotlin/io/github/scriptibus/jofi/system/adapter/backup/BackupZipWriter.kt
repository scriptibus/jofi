// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.domain.backup.BackupContents
import io.github.scriptibus.jofi.system.domain.backup.BackupEntry
import io.github.scriptibus.jofi.system.domain.backup.BackupManifest
import io.github.scriptibus.jofi.system.domain.backup.BackupPath
import io.github.scriptibus.jofi.system.domain.backup.BackupWorkspace
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.streams.asSequence

/**
 * Writes an archive of format version 1 (ADR-0042): the table dumps, the keyset and the data volume's
 * files, each streamed with its size and SHA-256, and the manifest last, when all checksums are known.
 */
internal class BackupZipWriter(
    private val dataDirectory: Path,
) {
    fun write(
        workspace: BackupWorkspace,
        contents: BackupContents,
        output: OutputStream,
    ): BackupManifest {
        // Not `use`: closing finishes the archive. On a failure it must stay unfinished (no central
        // directory, no manifest), so a broken export can never pass for a valid zip.
        val zip = ZipOutputStream(BufferedOutputStream(output))
        val entries = mutableListOf<BackupEntry>()
        contents.tables.forEach { table ->
            entries += addFile(zip, table.path, workspace.databaseDirectory.resolve("${table.name}.csv"))
        }
        contents.keyset?.let { entries += add(zip, BackupPath.KEYSET, ByteArrayInputStream(it.bytes())) }
        BackupPath.DATA_ROOTS.forEach { root -> entries += dataFiles(zip, root) }
        val manifest =
            BackupManifest(
                BackupManifest.FORMAT_VERSION,
                contents.appVersion,
                contents.schemaVersion,
                contents.createdAt,
                contents.tables,
                entries,
            )
        zip.putNextEntry(ZipEntry(BackupPath.MANIFEST.value))
        zip.write(ManifestJson.write(manifest))
        zip.closeEntry()
        zip.close()
        return manifest
    }

    // Regular files only: links are not followed, so nothing outside the data volume ends up in a backup.
    private fun dataFiles(
        zip: ZipOutputStream,
        root: String,
    ): List<BackupEntry> {
        val directory = dataDirectory.resolve(root)
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val (files, skipped) =
            Files
                .walk(directory)
                .use { paths ->
                    paths
                        .asSequence()
                        .filter {
                            it != directory &&
                                !Files.isDirectory(
                                    it,
                                    LinkOption.NOFOLLOW_LINKS,
                                )
                        }.toList()
                }.partition { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        if (skipped.isNotEmpty()) logger.warn("Skipped {} links or special files in {}", skipped.size, root)
        return files.sorted().map { file ->
            val relative = directory.relativize(file).joinToString("/")
            // Refused rather than skipped: a backup must not silently miss a file.
            val path =
                BackupPath.dataFile(root, relative) ?: throw IOException("A file name in $root cannot be backed up")
            addFile(zip, path, file)
        }
    }

    private fun addFile(
        zip: ZipOutputStream,
        path: BackupPath,
        file: Path,
    ): BackupEntry = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { add(zip, path, it) }

    private fun add(
        zip: ZipOutputStream,
        path: BackupPath,
        input: InputStream,
    ): BackupEntry {
        zip.putNextEntry(ZipEntry(path.value))
        val digest = Digests.copy(input, zip)
        zip.closeEntry()
        return BackupEntry(path, digest)
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(BackupZipWriter::class.java)
    }
}
