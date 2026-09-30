// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

import io.github.scriptibus.jofi.shared.application.ConfirmActionUseCase
import io.github.scriptibus.jofi.shared.application.port.ConfirmationStorePort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** Wiring shared by every context. */
@Configuration(proxyBeanMethods = false)
class SharedConfiguration {
    /** The one clock use cases and adapters read "now" from; tests replace it. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** The two-step confirmation every destructive or outward-facing use case goes through (ADR-0039). */
    @Bean
    fun confirmActionUseCase(
        store: ConfirmationStorePort,
        clock: Clock,
    ): ConfirmActionUseCase = ConfirmActionUseCase(store, clock, CONFIRMATION_TIME_TO_LIVE)

    private companion object {
        /** Long enough to read a dialog or an MCP elicitation, short enough that a leaked token soon dies. */
        val CONFIRMATION_TIME_TO_LIVE: Duration = Duration.ofMinutes(5)
    }
}
