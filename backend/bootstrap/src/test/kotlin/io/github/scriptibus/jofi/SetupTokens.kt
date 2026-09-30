// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import java.nio.file.Files
import java.nio.file.Path

/** The setup token in the test JVM's data volume, read the way the owner does (from the file). */
object SetupTokens {
    val file: Path
        get() {
            val dataDirectory = requireNotNull(System.getProperty("jofi.data-dir")) { "jofi.data-dir is not set" }
            return Path.of(dataDirectory, "secrets", "setup-token")
        }

    fun read(): String = Files.readString(file).trim()

    fun exists(): Boolean = Files.exists(file)
}
