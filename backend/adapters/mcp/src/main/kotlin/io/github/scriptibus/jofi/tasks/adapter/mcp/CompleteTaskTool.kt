// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.tasks.application.CompleteTaskUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component
import java.util.UUID

/** `complete_task`: marks an open task done, based on the version read; an edit, so no confirmation. */
@Component
class CompleteTaskTool(
    private val completeTask: CompleteTaskUseCase,
) : McpTool {
    override val name = "complete_task"
    override val readOnly = false
    override val description =
        "Mark an open task done. Give its `id` and the `version` you read (list_tasks); a stale version answers " +
            "version-conflict and changes nothing. A task that is done already is returned unchanged; a " +
            "suggestion or dismissed task cannot be completed (invalid-transition, accept a suggestion first)."
    override val inputSchema = TASK_VERSION_SCHEMA

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = complete(call, id, version)) {
            is TaskResult.Success -> ToolAnswer.Result(TaskDetailResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }
    }

    private fun complete(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = completeTask.execute(TaskId(id), version, call.caller)
}

/** The arguments of the tools that move a task: its id and the version they are based on. */
internal val TASK_VERSION_SCHEMA =
    """
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "version"],
      "properties": {
        "id": {"type": "string", "format": "uuid"},
        "version": {"type": "integer", "minimum": 0, "description": "The task's version as last read."}
      }
    }
    """.trimIndent()
