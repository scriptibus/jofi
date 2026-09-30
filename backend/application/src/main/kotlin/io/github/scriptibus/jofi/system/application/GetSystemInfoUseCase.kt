// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.domain.SystemInfo

/** Returns the name and version of the running Jofi instance. */
class GetSystemInfoUseCase(
    private val buildInfo: BuildInfoPort,
) {
    fun execute(): SystemInfo = SystemInfo.of(version = buildInfo.applicationVersion())
}
