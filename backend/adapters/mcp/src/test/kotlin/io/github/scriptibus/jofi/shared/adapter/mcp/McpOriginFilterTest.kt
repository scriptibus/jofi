// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class McpOriginFilterTest {
    private val filter = McpOriginFilter()

    @Test
    fun `a foreign Origin on the MCP endpoint is refused with 403 before anything else runs`() {
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(request("/mcp", "http://evil.example"), response, chain)

        response.status shouldBe 403
        chain.request.shouldBeNull()
    }

    @Test
    fun `Jofi's own origin, no Origin, and other paths pass`() {
        listOf(
            request("/mcp", "http://localhost:8080"),
            request("/mcp", null),
            request("/api/applications", "http://evil.example"),
            request("/mcpx", "http://evil.example"),
        ).forEach { request ->
            val chain = MockFilterChain()

            filter.doFilter(request, MockHttpServletResponse(), chain)

            chain.request.shouldNotBeNull()
        }
    }

    private fun request(
        path: String,
        origin: String?,
    ) = MockHttpServletRequest("POST", path).apply {
        addHeader("Host", "localhost:8080")
        origin?.let { addHeader("Origin", it) }
    }
}
