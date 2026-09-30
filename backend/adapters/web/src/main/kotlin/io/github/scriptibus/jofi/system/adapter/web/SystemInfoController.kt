// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.GetSystemInfoUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system")
class SystemInfoController(
    private val getSystemInfo: GetSystemInfoUseCase,
) {
    @GetMapping("/info")
    fun getSystemInfo(): SystemInfoResponse = SystemInfoResponse.from(getSystemInfo.execute())
}
