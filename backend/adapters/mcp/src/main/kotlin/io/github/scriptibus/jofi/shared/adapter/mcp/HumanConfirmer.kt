// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** What the user answered when the server asked them to confirm an action. */
enum class HumanAnswer {
    CONFIRMED,
    DECLINED,

    /** The client cannot ask its user (no elicitation), or asking failed: nothing may run. */
    UNAVAILABLE,
}

/**
 * Asks the human behind the client to confirm an action (ADR-0039, "MCP and the built-in chat"). The model never
 * sees the confirmation token: the tool keeps it server-side and uses it only after the human said yes.
 */
fun interface HumanConfirmer {
    fun ask(message: String): HumanAnswer

    companion object {
        /** For callers that cannot reach a human: every confirmation is unavailable. */
        val NONE = HumanConfirmer { HumanAnswer.UNAVAILABLE }
    }
}

/**
 * MCP elicitation (form mode) on the exchange of the running tool call: the client shows the message and one
 * checkbox, and its user answers. A client without form elicitation, a transport error or a timeout is
 * [HumanAnswer.UNAVAILABLE]; only an explicit "accept" with the box checked is [HumanAnswer.CONFIRMED].
 */
class ElicitingConfirmer(
    private val exchange: McpSyncServerExchange,
) : HumanConfirmer {
    @Suppress("TooGenericExceptionCaught") // The SDK throws unchecked transport and protocol errors.
    override fun ask(message: String): HumanAnswer {
        if (!supportsForms()) return HumanAnswer.UNAVAILABLE
        val request = McpSchema.ElicitFormRequest.builder(message, SCHEMA).build()
        return try {
            answerOf(exchange.createElicitation(request))
        } catch (failure: Exception) {
            log.warn("MCP confirmation could not be asked: {}", failure.javaClass.name)
            HumanAnswer.UNAVAILABLE
        }
    }

    private fun supportsForms(): Boolean {
        val elicitation = exchange.clientCapabilities?.elicitation() ?: return false
        // An empty `elicitation: {}` means form mode; a client that only supports URL mode cannot show a form.
        return elicitation.form() != null || elicitation.url() == null
    }

    private fun answerOf(result: McpSchema.ElicitResult): HumanAnswer =
        when {
            result.action() != McpSchema.ElicitResult.Action.ACCEPT -> HumanAnswer.DECLINED
            result.content()?.get(FIELD) == true -> HumanAnswer.CONFIRMED
            else -> HumanAnswer.DECLINED
        }

    private companion object {
        const val FIELD = "confirm"
        val log: Logger = LoggerFactory.getLogger(ElicitingConfirmer::class.java)
        val SCHEMA: Map<String, Any> =
            mapOf(
                "type" to "object",
                "properties" to
                    mapOf(
                        FIELD to
                            mapOf(
                                "type" to "boolean",
                                "title" to "Confirm",
                                "description" to "Check to confirm, or decline the request.",
                                "default" to false,
                            ),
                    ),
                "required" to listOf(FIELD),
            )
    }
}
