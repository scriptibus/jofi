// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * [SameOriginValidator] at the front of the security filter chain for [JofiMcpServer.ENDPOINT]: the MCP
 * transport rule says a request with a foreign `Origin` gets 403, also before authentication or CSRF would
 * answer 401 or 403 of their own. Other paths pass untouched. The transport checks again behind it.
 */
class McpOriginFilter : OncePerRequestFilter() {
    private val validator = SameOriginValidator()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return path != JofiMcpServer.ENDPOINT && !path.startsWith("${JofiMcpServer.ENDPOINT}/")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val headers =
            listOf(ORIGIN, HOST)
                .mapNotNull { name -> request.getHeader(name)?.let { name to listOf(it) } }
                .toMap()
        try {
            validator.validateHeaders(headers)
        } catch (refused: ServerTransportSecurityException) {
            response.sendError(refused.statusCode, "Invalid Origin header")
            return
        }
        chain.doFilter(request, response)
    }

    private companion object {
        const val ORIGIN = "Origin"
        const val HOST = "Host"
    }
}
