// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.DeleteInterviewUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.InterviewId
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.TwoStepDelete
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** `delete_interview`: deletes one interview of an application, confirmed by the user ([TwoStepDelete]). */
@Component
class DeleteInterviewTool(
    private val deleteInterview: DeleteInterviewUseCase,
) : McpTool {
    override val name = "delete_interview"
    override val readOnly = false
    override val description =
        "Delete one interview of a job application by the interview's id and the application's id. The user " +
            "is asked to confirm in their MCP client first; nothing is deleted without their yes. " +
            "Answers status deleted or declined."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["applicationId", "id"],
          "properties": {
            "applicationId": {"type": "string", "format": "uuid"},
            "id": {"type": "string", "format": "uuid"}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val application = call.arguments.uuid("applicationId") ?: throw InvalidToolArgument("applicationId")
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        return TwoStepDelete.run(
            call,
            id,
            { requester, token -> delete(application, id, requester, token) },
            { (it as? ApplicationResult.Unconfirmed)?.outcome },
            ApplicationToolErrors::deleted,
        )
    }

    // A named method, not the lambda above: the architecture rule sees the one use case call here.
    private fun delete(
        application: UUID,
        id: UUID,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> = deleteInterview.execute(ApplicationId(application), InterviewId(id), requester, token)
}
