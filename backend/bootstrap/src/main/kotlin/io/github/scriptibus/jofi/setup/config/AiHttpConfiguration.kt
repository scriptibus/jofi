// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.config

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import io.github.scriptibus.jofi.shared.adapter.net.Destination
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.adapter.net.UserAgent
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The HTTP transport of the AI provider adapter (ADR-0034, ADR-0039): the SSRF guard with the base
 * URLs of the configured AI providers as its allowlist, so a local Ollama may be private while every
 * other internal destination stays blocked. The allowlist is read on each new connection; if the
 * provider store is missing or fails, it is empty (fail closed). The vendor SDK clients built in
 * `setup.adapter.ai` get it through per-call SDK bridges (`AiProviderConfiguration`), never a
 * client of their own.
 */
@Configuration(proxyBeanMethods = false)
class AiHttpConfiguration {
    @Bean
    fun aiTransport(
        providerConfigs: ObjectProvider<ProviderConfigPort>,
        buildProperties: ObjectProvider<BuildProperties>,
    ): GuardedAiTransport =
        GuardedAiTransport.create(
            DestinationAllowlist { destination -> destination in configuredDestinations(providerConfigs) },
            UserAgent.of(buildProperties.ifAvailable?.version),
        )

    private fun configuredDestinations(providerConfigs: ObjectProvider<ProviderConfigPort>): Set<Destination> {
        val stored = providerConfigs.ifAvailable?.findAll() as? SetupStoreResult.Success ?: return emptySet()
        return stored.value.mapNotNull { config -> config.baseUri?.let(Destination::of) }.toSet()
    }
}
