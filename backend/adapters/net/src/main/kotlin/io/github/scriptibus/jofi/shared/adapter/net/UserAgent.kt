// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

/** The identifying `User-Agent` of every outbound request (threat model T9): name, version, project URL. */
object UserAgent {
    /** [version] is null when build info is missing (IDE runs, tests). */
    fun of(version: String?): String = "Jofi/${version ?: "dev"} (+https://github.com/scriptibus/jofi)"
}
