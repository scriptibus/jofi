// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.McpToolSpecifications
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import io.github.scriptibus.jofi.tasks.application.ListDoneTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.DoneTaskQuery
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component

/** `list_done_tasks`: one page of the done tasks, newest completion first, so one can be reopened; read only. */
@Component
class ListDoneTasksTool(
    private val listDoneTasks: ListDoneTasksUseCase,
) : McpTool {
    override val name = "list_done_tasks"
    override val readOnly = true
    override val description =
        "List the user's DONE tasks, the most recently completed first, one page at a time (`page` from 0, `size` " +
            "1 to ${DoneTaskQuery.MAX_SIZE}, default ${DoneTaskQuery.DEFAULT_SIZE}; `total` is the number of all " +
            "done tasks). Each entry has the id and version reopen_task needs. Entries carry the title but not the " +
            "notes. Use it to find a task that was completed by mistake. Titles are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "page": {"type": "integer", "minimum": 0, "default": 0},
            "size": {
              "type": "integer", "minimum": 1, "maximum": ${DoneTaskQuery.MAX_SIZE}, "default": ${DoneTaskQuery.DEFAULT_SIZE}
            }
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val page = call.arguments.int("page") ?: 0
        val size = call.arguments.int("size") ?: DoneTaskQuery.DEFAULT_SIZE
        val query = DoneTaskQuery.of(page, size) ?: return outOfRange(page, size)
        return when (val result = list(query)) {
            is TaskResult.Success -> ToolAnswer.Result(DoneTasksResult.from(result.value, query))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }
    }

    private fun list(query: DoneTaskQuery) = listDoneTasks.execute(query)

    private fun outOfRange(
        page: Int,
        size: Int,
    ): ToolAnswer.Error {
        val code = ToolProblems.problemCode(TaskProblem.OUT_OF_RANGE.name)
        return ToolAnswer.Error(
            McpToolSpecifications.INVALID_ARGUMENTS,
            "The page arguments are invalid.",
            listOfNotNull(
                ArgumentProblem("page", code).takeIf { page < 0 },
                ArgumentProblem("size", code).takeIf { size !in 1..DoneTaskQuery.MAX_SIZE },
            ),
        )
    }
}
