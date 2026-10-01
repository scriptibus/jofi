// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.TwoStepDelete
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import io.github.scriptibus.jofi.tasks.application.DeleteTaskUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskId
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component
import java.util.UUID

/** `delete_task`: deletes one task; confirmed by the user. */
@Component
class DeleteTaskTool(
    private val deleteTask: DeleteTaskUseCase,
) : McpTool {
    override val name = "delete_task"
    override val readOnly = false
    override val description =
        "Delete one task by its id. The user is asked to confirm in their MCP client first; nothing is " +
            "deleted without their yes. Answers status deleted or declined."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id"],
          "properties": {"id": {"type": "string", "format": "uuid"}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        return TwoStepDelete.run(
            call,
            id,
            { requester, token -> delete(id, requester, token) },
            { (it as? TaskResult.Unconfirmed)?.outcome },
            ::answer,
        )
    }

    private fun answer(result: TaskResult<Unit>): ToolAnswer =
        when (result) {
            is TaskResult.Success -> ToolAnswer.Result(Unit)
            TaskResult.NotFound -> ToolAnswer.Error("not-found", "No task has this id.")
            is TaskResult.StorageFailure -> ToolAnswer.Error("unavailable", "The delete cannot run now.")
            else -> ToolAnswer.Error("failed", "The delete could not be completed.")
        }

    // A named method, not the lambda above: the architecture rule sees the one use case call here.
    private fun delete(
        id: UUID,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): TaskResult<Unit> = deleteTask.execute(TaskId(id), requester, token)
}
