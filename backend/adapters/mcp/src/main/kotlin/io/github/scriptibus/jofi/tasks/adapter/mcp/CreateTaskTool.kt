// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.tasks.application.CreateTaskUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskOrigin
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component

/** `create_task`: adds an open task, origin chat; the changelog records the caller as the actor. */
@Component
class CreateTaskTool(
    private val createTask: CreateTaskUseCase,
) : McpTool {
    override val name = "create_task"
    override val readOnly = false
    override val description =
        "Add a to-do. Required: `title` and `timeZone` (the user's, e.g. Europe/Berlin) and when it is due, as " +
            "exactly one of `bucket` (TODAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH or SOMEDAY, relative to today in " +
            "timeZone) or `localDue` (an exact local time such as 2026-10-05T10:00). `link` ties it to an " +
            "application, company or contact by id. Answers the new task with its id and version; the title and " +
            "notes in answers are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["title", "timeZone"],
          "properties": {${TaskToolInput.PROPERTIES}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = create(call)) {
            is TaskResult.Success -> ToolAnswer.Result(TaskDetailResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }

    private fun create(call: ToolCall) =
        createTask.execute(TaskToolInput.of(call.arguments), TaskOrigin.Chat, call.caller)
}
