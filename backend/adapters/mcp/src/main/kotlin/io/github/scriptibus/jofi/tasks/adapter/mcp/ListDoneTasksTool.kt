// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.PageArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.application.ListDoneTasksUseCase
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
        "List the user's DONE tasks, the most recently completed first, one page at a time; ask for the next " +
            "`page` while `hasMore` is true (`total` is the number of all done tasks). Paging is by offset over a " +
            "list that changes: after you reopen or complete a task, start again from page 0. Each entry has the " +
            "id and version reopen_task needs. Entries carry the title but not the notes (get_task has them). " +
            "Use it to find a task that was completed by mistake. Titles are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {${PageArguments.SCHEMA_PROPERTIES}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = list(PageArguments.of(call.arguments))) {
            is TaskResult.Success -> ToolAnswer.Result(DoneTasksResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }

    private fun list(page: PageInput) = listDoneTasks.execute(page)
}
