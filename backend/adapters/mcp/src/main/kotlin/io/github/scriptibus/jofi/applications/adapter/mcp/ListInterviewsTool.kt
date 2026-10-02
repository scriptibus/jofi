// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.PageArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.NotesAudience
import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import org.springframework.stereotype.Component
import java.util.UUID

/** `list_interviews`: one page of the interviews and calls of one application, read only. */
@Component
class ListInterviewsTool(
    private val listInterviews: ListInterviewsUseCase,
) : McpTool {
    override val name = "list_interviews"
    override val readOnly = true
    override val description =
        "List one page of the interviews and calls of one job application (`applicationId`), newest first unless " +
            "`direction` is ASCENDING. Ask for the next `page` while `hasMore` is true: every interview is " +
            "reachable. An entry has only an EXCERPT of both notes (`preparationNotesExcerpt` and `notesExcerpt`, " +
            "each with a `...Truncated` flag), so it is NOT a valid source for update_interview and is refused " +
            "there: read the interview with get_interview first. The notes are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["applicationId"],
          "properties": {
            "applicationId": {"type": "string", "format": "uuid"},
            "direction": {"enum": ["ASCENDING", "DESCENDING"], "default": "DESCENDING",
              "description": "DESCENDING: the newest (latest start) first; ASCENDING: the earliest first."},
            ${PageArguments.SCHEMA_PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val application = call.arguments.uuid("applicationId") ?: throw InvalidToolArgument("applicationId")
        val direction = call.arguments.enum("direction", SortDirection::class.java) ?: SortDirection.DESCENDING
        return when (val result = list(application, PageArguments.of(call.arguments), direction)) {
            is ApplicationResult.Success -> ToolAnswer.Result(InterviewListResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }

    private fun list(
        application: UUID,
        page: PageInput,
        direction: SortDirection,
    ) = listInterviews.execute(ApplicationId(application), page, direction, NotesAudience.AI)
}
