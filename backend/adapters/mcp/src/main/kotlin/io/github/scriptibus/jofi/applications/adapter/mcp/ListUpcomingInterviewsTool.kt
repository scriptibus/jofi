// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.ListUpcomingInterviewsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `list_upcoming_interviews`: the interviews still to come across all applications, soonest first, read only. */
@Component
class ListUpcomingInterviewsTool(
    private val listUpcomingInterviews: ListUpcomingInterviewsUseCase,
) : McpTool {
    override val name = "list_upcoming_interviews"
    override val readOnly = true
    override val description =
        "List the interviews and calls still to come across all job applications, soonest first, without " +
            "their notes (list_interviews has them). Cancelled ones are left out. Each carries its " +
            "application's id and title; the title is marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = listUpcomingInterviews.execute()) {
            is ApplicationResult.Success -> ToolAnswer.Result(UpcomingInterviewsResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
}
