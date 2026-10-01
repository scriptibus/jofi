// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator
import java.net.URI
import java.net.URISyntaxException

/**
 * The Origin check the MCP Streamable HTTP transport requires (ADR-0053): a request that carries an
 * `Origin` (only browsers send one) must come from Jofi's own origin, i.e. name the host and port of its
 * `Host` header; anything else, `null` included, is refused with 403. MCP clients outside a browser send
 * no Origin. Jofi has no configured public host name, so this is a same-origin rule, not an allowlist.
 * DNS rebinding cannot pass it with a session: the session cookie belongs to Jofi's real host name, and
 * Spring Security refuses `/mcp` without one before this check runs.
 */
class SameOriginValidator : ServerTransportSecurityValidator {
    override fun validateHeaders(headers: Map<String, List<String>>) {
        val origin = first(headers, "origin") ?: return
        val host = first(headers, "host")
        if (host == null || !sameAuthority(origin, host)) {
            throw ServerTransportSecurityException(FORBIDDEN, "Invalid Origin header")
        }
    }

    private fun first(
        headers: Map<String, List<String>>,
        name: String,
    ): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private fun sameAuthority(
        origin: String,
        host: String,
    ): Boolean {
        val from = parse(origin) ?: return false
        val scheme = from.scheme?.lowercase()
        val target = parse("$scheme://$host")
        return scheme in DEFAULT_PORTS &&
            target != null &&
            from.host != null &&
            from.host.equals(target.host, ignoreCase = true) &&
            portOf(from) == portOf(target)
    }

    private fun parse(text: String): URI? =
        try {
            URI(text).takeIf { it.path.isNullOrEmpty() && it.query == null && it.userInfo == null }
        } catch (_: URISyntaxException) {
            null
        }

    private fun portOf(uri: URI): Int? = if (uri.port >= 0) uri.port else DEFAULT_PORTS[uri.scheme.lowercase()]

    private companion object {
        const val FORBIDDEN = 403
        val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)
    }
}
