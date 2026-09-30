// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import java.nio.file.Files
import java.nio.file.Path

/**
 * The `logging.level` entries of the app's real `application.yaml` (path from the build, system
 * property `jofi.application.yaml`), so the log privacy test runs with exactly the production
 * switches instead of a copy that could drift. A small reader for that one flat block.
 */
object AppLogLevels {
    private val ENTRY = Regex("^ {4}([\\w.$-]+):\\s*\"?([A-Za-z]+)\"?\\s*(#.*)?$")

    fun load(): Map<String, String> {
        val path =
            Path.of(
                requireNotNull(System.getProperty("jofi.application.yaml")) { "jofi.application.yaml not set" },
            )
        val lines = Files.readAllLines(path)
        val start = lines.indexOf("logging:")
        require(start >= 0 && lines[start + 1].trim() == "level:") { "No logging.level block in $path" }
        return lines
            .drop(start + 2)
            .takeWhile { it.isBlank() || it.startsWith("    ") }
            .mapNotNull { ENTRY.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
