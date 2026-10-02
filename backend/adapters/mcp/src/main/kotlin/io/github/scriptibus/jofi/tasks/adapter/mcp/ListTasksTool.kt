// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.PageArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.application.ListTaskGroupsUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskField
import io.github.scriptibus.jofi.tasks.domain.TaskProblem
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import io.github.scriptibus.jofi.tasks.domain.TaskTiming
import io.github.scriptibus.jofi.tasks.domain.TaskViolation
import org.springframework.stereotype.Component
import java.time.ZoneId

/** `list_tasks`: one page of the open tasks grouped by when they are due, on the user's calendar; read only. */
@Component
class ListTasksTool(
    private val listTaskGroups: ListTaskGroupsUseCase,
) : McpTool {
    override val name = "list_tasks"
    override val readOnly = true
    override val description =
        "List the user's OPEN tasks grouped by when they are due, as seen on the calendar of `timeZone` (an IANA " +
            "id such as Europe/Berlin or an offset such as +02:00; weeks start on Monday). Groups, always all and " +
            "in this order, empty ones included: OVERDUE (an exact time that has passed, or a day, week or month " +
            "that has ended), TODAY, THIS_WEEK, NEXT_WEEK, THIS_MONTH, LATER, SOMEDAY; each soonest first. A day, " +
            "week or month the user is in now counts as TODAY, THIS_WEEK or THIS_MONTH. Done tasks and " +
            "suggestions are not listed (suggestions: list_task_suggestions). The list is paged: the open tasks are " +
            "numbered through the groups in this order and `page`/`size` pick a window of them (every group is " +
            "always present, with the tasks of this page); ask for the next `page` while `hasMore` is true. A " +
            "list entry has only an EXCERPT of the notes (`notesExcerpt`, with `notesTruncated`): read the " +
            "whole task with get_task. Titles and notes are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["timeZone"],
          "properties": {
            "timeZone": {"type": "string", "maxLength": 64},
            ${PageArguments.SCHEMA_PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val zone =
            TaskTiming.zoneOf(call.arguments.text("timeZone").orEmpty())
                ?: return TaskToolErrors.failure(
                    TaskResult.Invalid(listOf(TaskViolation(TaskField.TIME_ZONE, TaskProblem.INVALID_TIME_ZONE))),
                )
        return when (val result = list(zone, PageArguments.of(call.arguments))) {
            is TaskResult.Success -> ToolAnswer.Result(TaskGroupsResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }
    }

    private fun list(
        zone: ZoneId,
        page: PageInput,
    ) = listTaskGroups.execute(zone, page)
}
