// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

/** Outbound port: information about the build that produced the running application. */
interface BuildInfoPort {
    /** The version of the running build, e.g. `0.1.0-SNAPSHOT`. */
    fun applicationVersion(): String
}
