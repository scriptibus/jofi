// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.DeleteApplicationUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.TwoStepDelete
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `delete_application`: deletes one application with its history, interviews and sources. The use case holds the
 * confirmation gate (ADR-0039); [TwoStepDelete] asks the user through MCP elicitation, and the model never sees
 * the token.
 */
@Component
class DeleteApplicationTool(
    private val deleteApplication: DeleteApplicationUseCase,
) : McpTool {
    override val name = "delete_application"
    override val readOnly = false
    override val description =
        "Delete one job application by its id, with its status history, interviews, sources and description " +
            "snapshots. The user is asked to confirm in their MCP client first; nothing is deleted without " +
            "their yes. Answers status deleted or declined."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id"],
          "properties": {"id": {"type": "string", "format": "uuid"}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        return TwoStepDelete.run(
            call,
            id,
            { requester, token -> delete(id, requester, token) },
            { (it as? ApplicationResult.Unconfirmed)?.outcome },
            ApplicationToolErrors::deleted,
        )
    }

    // A named method, not the lambda above: the architecture rule sees the one use case call here.
    private fun delete(
        id: UUID,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ApplicationResult<Unit> = deleteApplication.execute(ApplicationId(id), requester, token)
}
