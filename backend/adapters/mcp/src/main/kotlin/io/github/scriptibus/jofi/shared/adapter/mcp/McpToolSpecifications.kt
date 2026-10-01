// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.application.FilterToolResultUseCase
import io.github.scriptibus.jofi.shared.domain.ai.FilteredToolResult
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns [McpTool]s into MCP SDK tool specifications and runs every call the same way (ADR-0053): the caller
 * comes from the transport context (never from the arguments), argument errors become an `invalid-arguments`
 * result, and every result and error is serialised and passed through the "never send to AI" filter before it
 * leaves. If the flags cannot be read, the call answers `privacy-filter-failed` and nothing of the result.
 */
class McpToolSpecifications(
    private val results: JsonMapper,
    private val protocol: McpJsonMapper,
    private val filter: FilterToolResultUseCase,
) {
    /** The sessions that wait for an answer to a confirmation: at most one each, as a waiting call holds a thread. */
    private val pendingConfirmations: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun of(tool: McpTool): McpServerFeatures.SyncToolSpecification =
        McpServerFeatures.SyncToolSpecification
            .builder()
            .tool(definitionOf(tool))
            .callHandler { exchange, request ->
                call(
                    tool,
                    exchange.transportContext(),
                    request.arguments().orEmpty(),
                    exchange.sessionId() ?: ToolCall.NO_SESSION,
                    confirmerFor(exchange),
                )
            }.build()

    /** Asks through [exchange]; what it asks passes the "never send to AI" filter first, or nothing is asked. */
    internal fun confirmerFor(exchange: McpSyncServerExchange) =
        ElicitingConfirmer(exchange, pendingConfirmations, ::filteredText)

    @Suppress("TooGenericExceptionCaught") // Any failure of the filter means: ask nothing.
    private fun filteredText(text: String): String? =
        try {
            when (val result = filter.execute(results.writeValueAsString(text))) {
                is FilteredToolResult.Passed -> results.readValue(result.json, String::class.java)
                FilteredToolResult.Refused -> null
            }
        } catch (failure: Exception) {
            log.error("MCP confirmation text withheld: {}", failure.javaClass.name)
            null
        }

    /** One call of [tool] with the raw [arguments], for the caller in [context]. */
    fun call(
        tool: McpTool,
        context: McpTransportContext,
        arguments: Map<String, Any?>,
        session: String = ToolCall.NO_SESSION,
        human: HumanConfirmer = HumanConfirmer.NONE,
    ): McpSchema.CallToolResult {
        val caller = McpCallers.actorOf(context) ?: return answer(UNAUTHENTICATED)
        return answer(run(tool, ToolCall(ToolArguments(arguments), caller, session, human)))
    }

    private fun definitionOf(tool: McpTool): McpSchema.Tool =
        McpSchema.Tool
            .builder(tool.name, protocol, tool.inputSchema)
            .description(tool.description)
            .annotations(
                McpSchema.ToolAnnotations
                    .builder()
                    .readOnlyHint(tool.readOnly)
                    .openWorldHint(false)
                    .build(),
            ).build()

    @Suppress("TooGenericExceptionCaught") // Any failure, checked ones from Java code too, must not leave as a message.
    private fun run(
        tool: McpTool,
        call: ToolCall,
    ): ToolAnswer =
        try {
            tool.call(call)
        } catch (invalid: InvalidToolArgument) {
            ToolAnswer.Error(INVALID_ARGUMENTS, "An argument has the wrong type or format.", listOf(invalid.problem()))
        } catch (failure: Exception) {
            log.error("MCP tool {} failed: {}", tool.name, failure.javaClass.name)
            ToolAnswer.Error("internal-error", "The tool failed unexpectedly.")
        }

    private fun answer(answer: ToolAnswer): McpSchema.CallToolResult =
        when (answer) {
            is ToolAnswer.Result -> filtered(answer.value, isError = false)
            is ToolAnswer.Error -> filtered(answer, isError = true)
        }

    /**
     * Fail closed: if serialising, reading the flags or redacting throws, the SDK would send the exception's
     * message (and its causes) to the client unfiltered, so every failure here answers privacy-filter-failed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun filtered(
        value: Any,
        isError: Boolean,
    ): McpSchema.CallToolResult =
        try {
            when (val result = filter.execute(results.writeValueAsString(value))) {
                is FilteredToolResult.Passed -> textResult(result.json, isError)
                FilteredToolResult.Refused -> withheld(value)
            }
        } catch (failure: Exception) {
            log.error("MCP tool result withheld: {}", failure.javaClass.name)
            withheld(value)
        }

    /** A delete that already happened must not be reported as a failure: say so with the ids, nothing stored. */
    private fun withheld(value: Any): McpSchema.CallToolResult =
        if (value is DeleteOutcome && value.status == DeleteOutcome.DELETED) {
            textResult(
                """{"status":"deleted","kind":"${value.kind}","id":"${value.id}","note":"The delete happened; the """ +
                    """rest of the result was withheld."}""",
                false,
            )
        } else {
            textResult(PRIVACY_FILTER_FAILED, true)
        }

    private fun textResult(
        json: String,
        isError: Boolean,
    ): McpSchema.CallToolResult =
        McpSchema.CallToolResult
            .builder()
            .addTextContent(json)
            .isError(isError)
            .build()

    private fun InvalidToolArgument.problem() = ArgumentProblem(argument, "invalid")

    companion object {
        const val INVALID_ARGUMENTS = "invalid-arguments"
        private val log = LoggerFactory.getLogger(McpToolSpecifications::class.java)
        private val UNAUTHENTICATED = ToolAnswer.Error("unauthenticated", "The call carries no authenticated caller.")

        /** A constant, so answering it cannot fail in turn. */
        private const val PRIVACY_FILTER_FAILED =
            """{"code":"privacy-filter-failed","message":"The result was withheld: the privacy flags could not """ +
                """be read.","problems":[]}"""
    }
}
