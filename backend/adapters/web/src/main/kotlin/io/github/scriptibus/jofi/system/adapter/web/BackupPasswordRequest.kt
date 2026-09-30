// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

/** The current password, which every backup export and restore needs again (ADR-0042). */
data class BackupPasswordRequest(
    val password: String,
) {
    override fun toString(): String = "BackupPasswordRequest(password=***)"
}
