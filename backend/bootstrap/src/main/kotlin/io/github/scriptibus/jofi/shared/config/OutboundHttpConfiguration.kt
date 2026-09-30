// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.DestinationGuard
import io.github.scriptibus.jofi.shared.adapter.net.OutboundHttpAdapter
import io.github.scriptibus.jofi.shared.adapter.net.UserAgent
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the SSRF guard (threat model T1). Fetches of user- or posting-supplied URLs never get an
 * allowlist: only the AI client may reach internal destinations (ADR-0034, `AiHttpConfiguration`).
 */
@Configuration(proxyBeanMethods = false)
class OutboundHttpConfiguration {
    @Bean
    fun outboundHttpPort(buildProperties: ObjectProvider<BuildProperties>): OutboundHttpPort =
        OutboundHttpAdapter(
            DestinationGuard(DestinationAllowlist.NONE),
            UserAgent.of(buildProperties.ifAvailable?.version),
        )
}
