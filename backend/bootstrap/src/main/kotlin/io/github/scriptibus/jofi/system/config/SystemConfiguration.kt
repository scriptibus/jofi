// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.config

import io.github.scriptibus.jofi.system.application.GetSystemInfoUseCase
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the `system` context's use cases; domain and application stay free of Spring. */
@Configuration(proxyBeanMethods = false)
class SystemConfiguration {
    @Bean
    fun getSystemInfoUseCase(buildInfo: BuildInfoPort): GetSystemInfoUseCase = GetSystemInfoUseCase(buildInfo)
}
