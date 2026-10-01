// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.config

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

    @Bean(destroyMethod = "close")
    fun jofiMcpServer(
        tools: List<McpTool>,
        json: JsonMapper,
        filter: FilterToolResultUseCase,
        buildProperties: ObjectProvider<BuildProperties>,
        @Value("\${jofi.mcp.confirmation-timeout:PT4M30S}") confirmationTimeout: Duration,
    ): JofiMcpServer {
        val protocol = JacksonMcpJsonMapper(JsonMapper.builder().build())
        return JofiMcpServer(
            tools,
            McpToolSpecifications(json, protocol, filter),
            protocol,
            buildProperties.ifAvailable?.version ?: "dev",
            confirmationTimeout,
        )
    }

    @Bean
    fun mcpRoutes(server: JofiMcpServer): RouterFunction<ServerResponse> = server.routes
}
