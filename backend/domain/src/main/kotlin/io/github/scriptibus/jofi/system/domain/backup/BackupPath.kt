// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain.backup

/**
 * The path of one entry in a backup archive, relative to its root, `/`-separated (ADR-0042). Only
 * the layout of format version 1 is accepted, and never a path that could leave the directory the
 * archive is unpacked into (zip slip): no absolute paths, no empty, `.` or `..` segments, no
 * backslashes or control characters.
 */
@JvmInline
value class BackupPath private constructor(
    val value: String,
) {
    /** Whether this is a file of the data volume (knowledge, documents). */
    val isDataFile: Boolean get() = value.startsWith(FILES)

    override fun toString(): String = value

    companion object {
        private const val MAX_LENGTH = 1024
        private const val MAX_SEGMENT_LENGTH = 255
        private const val DATABASE = "database/"
        private const val FILES = "files/"
        private const val TABLE_SUFFIX = ".csv"
        private val TABLE_NAME = Regex("[a-z_][a-z0-9_]*")

        /** The manifest: format and versions, the table dumps, every other entry with its checksum. */
        val MANIFEST = BackupPath("manifest.json")

        /** The master keyset (Tink JSON, ADR-0035) that encrypts the `secret` table. */
        val KEYSET = BackupPath("secrets/master-keyset.json")

        /** The directories of the data volume a backup carries, as `files/<root>/...`. */
        val DATA_ROOTS: List<String> = listOf("knowledge", "documents")

        /** Where the CSV dump of [name] lives. */
        fun table(name: String): BackupPath {
            require(TABLE_NAME.matches(name)) { "Not a table name: $name" }
            return BackupPath("$DATABASE$name$TABLE_SUFFIX")
        }

        /** A file below one of [DATA_ROOTS], or `null` when [relative] is not a safe relative path. */
        fun dataFile(
            root: String,
            relative: String,
        ): BackupPath? = if (root in DATA_ROOTS) parse("$FILES$root/$relative") else null

        /** [name] as an archive names it, or `null` when format version 1 has no such path. */
        fun parse(name: String): BackupPath? = if (isSafe(name) && isInLayout(name)) BackupPath(name) else null

        private fun isInLayout(name: String): Boolean =
            name == MANIFEST.value || name == KEYSET.value || isTable(name) || isData(name)

        private fun isSafe(name: String): Boolean = name.length <= MAX_LENGTH && name.split('/').all(::isSafeSegment)

        private fun isSafeSegment(segment: String): Boolean =
            segment.isNotEmpty() &&
                segment.length <= MAX_SEGMENT_LENGTH &&
                segment != "." &&
                segment != ".." &&
                segment.none { it == '\\' || it.isISOControl() }

        private fun isTable(name: String): Boolean =
            name.startsWith(DATABASE) &&
                name.endsWith(TABLE_SUFFIX) &&
                TABLE_NAME.matches(name.removePrefix(DATABASE).removeSuffix(TABLE_SUFFIX))

        private fun isData(name: String): Boolean =
            DATA_ROOTS.any { root -> name.startsWith("$FILES$root/") && name.length > "$FILES$root/".length }
    }
}
