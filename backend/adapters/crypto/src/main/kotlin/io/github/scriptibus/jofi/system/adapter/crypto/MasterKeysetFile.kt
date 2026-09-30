// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.GeneralSecurityException

/** Where the master keyset lives, how it is read and how its check value is made (ADR-0035). */
internal object MasterKeysetFile {
    private const val SECRETS_DIRECTORY = "secrets"
    private const val KEYSET_FILE = "master-keyset.json"
    private val CHECK_PLAINTEXT = "jofi master key check".toByteArray(Charsets.UTF_8)
    private val CHECK_ASSOCIATED_DATA = "jofi:master-key-check".toByteArray(Charsets.UTF_8)

    /** `<data-dir>/secrets/master-keyset.json`. */
    fun of(dataDirectory: String): Path =
        DataDirectory.of(dataDirectory).resolve(SECRETS_DIRECTORY).resolve(KEYSET_FILE)

    /** The AEAD primitive of a keyset in Tink's JSON format; throws when it is not one. */
    fun primitive(json: String): Aead =
        TinkJsonProtoKeysetFormat
            .parseKeyset(json, InsecureSecretKeyAccess.get())
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    /** Identifies the file's current content (file key, modification time, size); `null` when it is missing. */
    fun version(file: Path): List<Any?>? =
        try {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            listOf(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
        } catch (_: IOException) {
            null
        }

    fun newCheckValue(primitive: Aead): ByteArray = primitive.encrypt(CHECK_PLAINTEXT, CHECK_ASSOCIATED_DATA)

    /** Whether [checkValue] was made with [primitive]'s keyset. */
    fun verifies(
        primitive: Aead,
        checkValue: ByteArray,
    ): Boolean =
        try {
            primitive.decrypt(checkValue, CHECK_ASSOCIATED_DATA).contentEquals(CHECK_PLAINTEXT)
        } catch (_: GeneralSecurityException) {
            false
        }
}
