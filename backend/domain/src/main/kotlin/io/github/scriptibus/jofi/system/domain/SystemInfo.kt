// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

/** Identity of the running Jofi instance: product name and build version. */
data class SystemInfo(
    val name: String,
    val version: String,
) {
    init {
        require(name.isNotBlank()) { "System name must not be blank" }
        require(version.isNotBlank()) { "System version must not be blank" }
    }

    companion object {
        const val PRODUCT_NAME: String = "Jofi"

        /** Info for this product at the given build [version]. */
        fun of(version: String): SystemInfo = SystemInfo(name = PRODUCT_NAME, version = version)
    }
}
