// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.ListInterviewsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `list_interviews`: the interviews and calls of one application in the order they start, read only. */
@Component
class ListInterviewsTool(
    private val listInterviews: ListInterviewsUseCase,
) : McpTool {
    override val name = "list_interviews"
    override val readOnly = true
    override val description =
        "List the interviews and calls of one job application (`applicationId`) in the order they start, with " +
            "their versions, for update_interview. At most ${InterviewListResult.MAX_LISTED} are returned, the " +
            "earliest ones; `total` is their number (#236 bounds this properly). The notes are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["applicationId"],
          "properties": {"applicationId": {"type": "string", "format": "uuid"}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val application = call.arguments.uuid("applicationId") ?: throw InvalidToolArgument("applicationId")
        return when (val result = listInterviews.execute(ApplicationId(application))) {
            is ApplicationResult.Success -> ToolAnswer.Result(InterviewListResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }
}
