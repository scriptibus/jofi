// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.SystemInfo

/** JSON body of `GET /api/system/info`. Kept separate from the domain type on purpose. */
data class SystemInfoResponse(
    val name: String,
    val version: String,
) {
    companion object {
        fun from(info: SystemInfo): SystemInfoResponse = SystemInfoResponse(name = info.name, version = info.version)
    }
}
