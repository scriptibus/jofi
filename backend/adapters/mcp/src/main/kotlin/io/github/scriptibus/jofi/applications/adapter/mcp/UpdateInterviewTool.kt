// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.UpdateInterviewUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component

/** `update_interview`: replaces all details of an interview, based on the version the caller read. */
@Component
class UpdateInterviewTool(
    private val updateInterview: UpdateInterviewUseCase,
) : McpTool {
    override val name = "update_interview"
    override val readOnly = false
    override val description =
        "Replace ALL details of an interview: a field left out is cleared, so call list_interviews first, change " +
            "what you mean to change and send everything back with the interview's `version`: `type`, " +
            "`localStart`, `timeZone`, `participantIds`, `outcome` as they are, `preparationNotes` and `notes` " +
            "from `interview`, and `null` for what is not set. A stale version answers version-conflict and " +
            "changes nothing. A value that shows [withheld] is hidden from you; sending it back is refused " +
            "(withheld-value). Answers the interview."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["applicationId", "id", "version", "type", "localStart", "timeZone"],
          "properties": {
            "applicationId": {"type": "string", "format": "uuid"},
            "id": {"type": "string", "format": "uuid", "description": "The interview."},
            "version": {"type": "integer", "minimum": 0, "description": "The version from list_interviews."},
            ${InterviewToolInput.PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldArgument()?.let { return ToolProblems.withheldValue(it) }
        return when (val result = update(call)) {
            is ApplicationResult.Success -> ToolAnswer.Result(InterviewResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }

    private fun update(call: ToolCall): ApplicationResult<Interview> {
        val arguments = call.arguments
        return updateInterview.execute(
            ApplicationId(InterviewToolInput.id(arguments, "applicationId")),
            InterviewId(InterviewToolInput.id(arguments, "id")),
            InterviewToolInput.of(arguments),
            InterviewToolInput.version(arguments),
            call.caller,
        )
    }
}
