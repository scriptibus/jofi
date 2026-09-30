// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.adapter.ai.ProviderPrivacyFile
import io.github.scriptibus.jofi.setup.application.ListProviderPrivacyInfoUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The provider privacy info for the setup wizard (#138, ADR-0044). */
@Configuration(proxyBeanMethods = false)
class ProviderPrivacyConfiguration {
    /** Reads the dated provider privacy file once at startup; a broken file stops the app. */
    @Bean
    fun listProviderPrivacyInfoUseCase(clock: Clock): ListProviderPrivacyInfoUseCase =
        ListProviderPrivacyInfoUseCase(ProviderPrivacyFile.load(), clock)
}
