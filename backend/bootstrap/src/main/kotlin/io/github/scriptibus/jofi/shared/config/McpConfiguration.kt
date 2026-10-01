// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

import io.github.scriptibus.jofi.shared.adapter.mcp.ConfirmationSlots
import io.github.scriptibus.jofi.shared.adapter.mcp.JofiMcpServer
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.McpToolSpecifications
import io.github.scriptibus.jofi.shared.application.FilterToolResultUseCase
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * The MCP server at `/mcp` (ADR-0012, ADR-0053), wired by hand: no Spring AI starter, so nothing else is
 * auto-configured. Every [McpTool] bean of every context is served; `SecurityConfiguration` guards the path.
 * A person has `jofi.mcp.confirmation-timeout` (default 4.5 minutes, below the confirmation's 5) to answer a
 * delete confirmation. The protocol uses the SDK's own Jackson 3 mapper, tool results the app's (Kotlin, `java.time`).
 */
@Configuration(proxyBeanMethods = false)
class McpConfiguration {
    @Bean
    fun filterToolResultUseCase(visibility: AiVisibilityPort): FilterToolResultUseCase =
        FilterToolResultUseCase(visibility)

    /** How long a person has to answer a delete confirmation and how many may wait (`docs/mcp-tools.md`). */
    @Bean
    fun mcpConfirmationSettings(
        @Value("\${jofi.mcp.confirmation-timeout:PT4M30S}") timeout: Duration,
        @Value("\${jofi.mcp.max-pending-confirmations:4}") maxPending: Int,
    ): McpConfirmationSettings =
        McpConfirmationSettings(
            validConfirmationTimeout(timeout, SharedConfiguration.CONFIRMATION_TIME_TO_LIVE),
            maxPending,
        )

    @Bean(destroyMethod = "close")
    fun jofiMcpServer(
        tools: List<McpTool>,
        json: JsonMapper,
        filter: FilterToolResultUseCase,
        buildProperties: ObjectProvider<BuildProperties>,
        confirmations: McpConfirmationSettings,
    ): JofiMcpServer {
        val protocol = JacksonMcpJsonMapper(JsonMapper.builder().build())
        return JofiMcpServer(
            tools,
            McpToolSpecifications(json, protocol, filter, ConfirmationSlots(confirmations.maxPending)),
            protocol,
            buildProperties.ifAvailable?.version ?: "dev",
            confirmations.timeout,
        )
    }

    @Bean
    fun mcpRoutes(server: JofiMcpServer): RouterFunction<ServerResponse> = server.routes

    companion object {
        /** Fails the start with a clear message: a timeout that is zero or reaches the token's lifetime cannot work. */
        fun validConfirmationTimeout(
            timeout: Duration,
            tokenLifetime: Duration,
        ): Duration {
            require(!timeout.isNegative && !timeout.isZero && timeout < tokenLifetime) {
                "jofi.mcp.confirmation-timeout must be positive and shorter than the confirmation token's " +
                    "lifetime ($tokenLifetime), but is $timeout"
            }
            return timeout
        }
    }
}

class McpConfirmationSettings(
    val timeout: Duration,
    val maxPending: Int,
)
