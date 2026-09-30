// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.adapter.ai.ModelCatalogAdapter
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderModels
import io.github.scriptibus.jofi.setup.adapter.ai.SpringAiProviderAdapter
import io.github.scriptibus.jofi.setup.application.port.ModelCatalogPort
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The Spring AI provider adapter (ADR-0039). Spring AI itself is not auto-configured (no starter on
 * the classpath): every model is built per call over the guarded SDK transports. The AI gateway
 * (#20) is the only user of `AiProviderPort`; this bean is typed as the adapter because nothing
 * outside `setup.adapter.ai` may name that port (architecture test).
 */
@Configuration(proxyBeanMethods = false)
class AiProviderConfiguration {
    @Bean
    fun providerModels(aiTransport: GuardedAiTransport): ProviderModels =
        // A fresh bridge per call, so each call can abort what it leaves open (ADR-0039).
        ProviderModels(aiTransport::openAiBridge, aiTransport::anthropicBridge)

    @Bean
    fun aiProviderPort(
        providerModels: ProviderModels,
        secretStore: SecretStorePort,
    ): SpringAiProviderAdapter = SpringAiProviderAdapter(providerModels, secretStore)

    @Bean
    fun modelCatalogPort(
        providerModels: ProviderModels,
        secretStore: SecretStorePort,
    ): ModelCatalogPort = ModelCatalogAdapter(providerModels, secretStore, Clock.systemUTC())
}
