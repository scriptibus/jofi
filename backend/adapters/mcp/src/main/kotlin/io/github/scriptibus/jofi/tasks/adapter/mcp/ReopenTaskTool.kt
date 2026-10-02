// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.tasks.application.ReopenTaskUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component
import java.util.UUID

/** `reopen_task`: opens a done task again, based on the version read; an edit, so no confirmation. */
@Component
class ReopenTaskTool(
    private val reopenTask: ReopenTaskUseCase,
) : McpTool {
    override val name = "reopen_task"
    override val readOnly = false
    override val description =
        "Open a done task again (find it with list_done_tasks). Give its `id` and the `version` you read; a stale " +
            "version answers version-conflict and changes nothing. A task that is open already is returned " +
            "unchanged; a suggestion or dismissed task cannot be reopened (invalid-transition)."
    override val inputSchema = TASK_VERSION_SCHEMA

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = reopen(call, id, version)) {
            is TaskResult.Success -> ToolAnswer.Result(TaskDetailResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }
    }

    private fun reopen(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = reopenTask.execute(TaskId(id), version, call.caller)
}
