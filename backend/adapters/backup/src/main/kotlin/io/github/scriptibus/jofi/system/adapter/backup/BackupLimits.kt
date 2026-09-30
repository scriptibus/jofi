// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

/**
 * What an uploaded backup may cost (`jofi.backup.*`): the upload itself, everything unpacked (zip
 * bombs) and the number of entries. Keyset and manifest, which are read into memory, have their own bounds.
 */
internal data class BackupLimits(
    val maxUploadBytes: Long,
    val maxUnpackedBytes: Long,
    val maxEntries: Int,
) {
    init {
        require(maxUploadBytes > 0 && maxUnpackedBytes > 0 && maxEntries > 0) { "Backup limits must be positive" }
    }

    companion object {
        const val MAX_KEYSET_BYTES = 64L * 1024

        /** The manifest is read into memory; 100,000 entries with long paths fit well below this. */
        const val MAX_MANIFEST_BYTES = 32L * 1024 * 1024
    }
}
