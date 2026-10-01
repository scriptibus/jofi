// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.Actor

/**
 * One MCP tool (ADR-0012, ADR-0053), a Spring bean in `<context>.adapter.mcp`. Like a controller it holds no
 * logic: it translates its arguments, calls exactly one use case and maps the result (`McpToolRules`).
 * The server ([McpToolSpecifications]) resolves the caller, serialises the answer and runs the
 * "never send to AI" filter on it, so no tool can skip it.
 */
interface McpTool {
    /** Unique, `snake_case`. */
    val name: String

    /** What the model reads to decide when and how to call the tool. */
    val description: String

    /** The JSON Schema (an object) of the arguments; the SDK checks every call against it. */
    val inputSchema: String

    /** True for tools that change nothing (the MCP `readOnlyHint`). */
    val readOnly: Boolean

    fun call(call: ToolCall): ToolAnswer
}

/**
 * One call: the [arguments] the client sent, and what the server knows itself (never arguments): the authenticated
 * [caller], the MCP [session] the call runs in (a confirmation is bound to it) and the [human] it can ask to
 * confirm a delete. Without a human to ask, confirmations are unavailable and nothing destructive runs.
 */
class ToolCall(
    val arguments: ToolArguments,
    val caller: Actor,
    val session: String = NO_SESSION,
    val human: HumanConfirmer = HumanConfirmer.NONE,
) {
    companion object {
        /** A session id that no MCP session has; a confirmation bound to it is never redeemed. */
        const val NO_SESSION = "no-session"
    }
}

/** What a tool answers. */
sealed interface ToolAnswer {
    /** Serialised to JSON as the tool result. */
    data class Result(
        val value: Any,
    ) : ToolAnswer

    /** A failed call: a stable [code], a [message] without stored content, and the [problems] by argument. */
    data class Error(
        val code: String,
        val message: String,
        val problems: List<ArgumentProblem> = emptyList(),
    ) : ToolAnswer
}

/** What is wrong with one argument, as codes the model can act on. */
data class ArgumentProblem(
    val argument: String,
    val problem: String,
)
