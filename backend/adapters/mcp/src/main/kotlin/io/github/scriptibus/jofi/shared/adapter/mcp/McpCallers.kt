// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.Actor
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpTransportContextExtractor
import org.springframework.web.servlet.function.ServerRequest

/**
 * Who calls a tool, decided by the server from the authenticated request and carried in the MCP transport
 * context; never from tool arguments (ADR-0053). Spring Security has already refused requests without a
 * session. A session caller is an AI working for the logged-in user (the built-in chat, spec §9), so its
 * changes are recorded as [Actor.Ai]; external clients get their own token and actor with #125.
 */
object McpCallers {
    private const val ACTOR = "jofi.actor"

    /** Reads the caller on the request thread, before the SDK hands the call to its own threads. */
    val extractor: McpTransportContextExtractor<ServerRequest> =
        McpTransportContextExtractor { request ->
            if (request.principal().isPresent) {
                McpTransportContext.create(mapOf(ACTOR to Actor.Ai))
            } else {
                McpTransportContext.EMPTY
            }
        }

    /** The caller's actor, or null if the request carried no authenticated caller. */
    fun actorOf(context: McpTransportContext): Actor? = context.get(ACTOR) as? Actor
}
