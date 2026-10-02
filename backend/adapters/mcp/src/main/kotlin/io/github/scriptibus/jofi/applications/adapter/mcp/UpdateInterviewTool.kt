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
        "Replace ALL details of an interview. Build the call from an entry of list_interviews that you read in " +
            "this session (list_upcoming_interviews does not return enough): never invent the nulls or the " +
            "version, because a null deletes the user's notes or participants for good. Change what you mean to " +
            "change and send it back with the same keys and nesting: " +
            "`applicationId`, `id`, `version`, `type`, `localStart`, `timeZone`, `participantIds`, `outcome` as " +
            "they are and the `content` of `interview` {preparationNotes, notes}. Do not send `readOnly`. Every " +
            "property is required: leaving one out is refused, only an explicit `null` clears a field " +
            "(`participantIds`: null or [] for none). A stale version answers version-conflict and changes " +
            "nothing. A value that shows [withheld] is hidden from you; sending it back is refused " +
            "(withheld-value) and names the argument, so an interview with a hidden value cannot be updated " +
            "through this tool. Answers the interview."
    override val inputSchema = InterviewToolInput.schema(update = true)

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldPath()?.let { return ToolProblems.withheldValue(it) }
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
