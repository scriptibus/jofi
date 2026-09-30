// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wiring shared by every context. */
@Configuration(proxyBeanMethods = false)
class SharedConfiguration {
    /** The one clock use cases and adapters read "now" from; tests replace it. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
