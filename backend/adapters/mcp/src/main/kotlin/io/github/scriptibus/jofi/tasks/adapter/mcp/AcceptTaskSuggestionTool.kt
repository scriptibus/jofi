// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.tasks.application.AcceptTaskSuggestionUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component
import java.util.UUID

/** `accept_task_suggestion`: turns a suggested task into an open one; an edit, so no confirmation. */
@Component
class AcceptTaskSuggestionTool(
    private val acceptSuggestion: AcceptTaskSuggestionUseCase,
) : McpTool {
    override val name = "accept_task_suggestion"
    override val readOnly = false
    override val description =
        "Accept a suggested task (list_task_suggestions) so it becomes an open task of the user. Give its `id` " +
            "and the `version` you read; a stale version answers version-conflict and changes nothing. A task " +
            "that is open already is returned unchanged; a done or dismissed one cannot be accepted " +
            "(invalid-transition)."
    override val inputSchema = TASK_VERSION_SCHEMA

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = accept(call, id, version)) {
            is TaskResult.Success -> ToolAnswer.Result(TaskDetailResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }
    }

    private fun accept(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = acceptSuggestion.execute(TaskId(id), version, call.caller)
}
