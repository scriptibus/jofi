// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.PageArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.tasks.application.ListSuggestedTasksUseCase
import io.github.scriptibus.jofi.tasks.domain.TaskResult
import org.springframework.stereotype.Component

/** `list_task_suggestions`: the suggested tasks waiting for a yes, so `accept_task_suggestion` has ids; read only. */
@Component
class ListTaskSuggestionsTool(
    private val listSuggestions: ListSuggestedTasksUseCase,
) : McpTool {
    override val name = "list_task_suggestions"
    override val readOnly = true
    override val description =
        "List one page of the tasks the app suggested (for example a follow-up) that wait for the user's yes, " +
            "newest first, each with the id and version accept_task_suggestion needs; ask for the next `page` " +
            "while `hasMore` is true. An entry has only an EXCERPT of the notes (`notesExcerpt`, with " +
            "`notesTruncated`): read the whole task with get_task. Titles and notes are marked untrusted."
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
            is TaskResult.Success -> ToolAnswer.Result(TaskSuggestionsResult.from(result.value))
            is TaskResult.Failure -> TaskToolErrors.failure(result)
        }

    private fun list(page: PageInput) = listSuggestions.execute(page, NotesAudience.AI)
}
