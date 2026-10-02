// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.LogInterviewUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.Interview
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `log_interview`: logs an interview or call of an application; the changelog records the caller as the actor. */
@Component
class LogInterviewTool(
    private val logInterview: LogInterviewUseCase,
) : McpTool {
    override val name = "log_interview"
    override val readOnly = false
    override val description =
        "Log an interview or call of a job application: `applicationId`, `type`, `localStart` (the agreed " +
            "wall-clock time such as 2026-10-05T10:00) and `timeZone` (such as Europe/Berlin) are required. " +
            "`participantIds` are contacts that took part; `preparationNotes` and `notes` are Markdown; " +
            "`outcome` once known. A local time a clock change skips is moved on: check `localStart` in the " +
            "answer. Answers the interview with its id and version; the notes in answers are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["applicationId", "type", "localStart", "timeZone"],
          "properties": {
            "applicationId": {"type": "string", "format": "uuid"},
            ${InterviewToolInput.PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = log(call)) {
            is ApplicationResult.Success -> ToolAnswer.Result(InterviewResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }

    // A named method, not a lambda: the architecture rule sees the one use case call here.
    private fun log(call: ToolCall): ApplicationResult<Interview> {
        val application = call.arguments.uuid("applicationId") ?: throw InvalidToolArgument("applicationId")
        return logInterview.execute(ApplicationId(application), InterviewToolInput.of(call.arguments), call.caller)
    }
}
