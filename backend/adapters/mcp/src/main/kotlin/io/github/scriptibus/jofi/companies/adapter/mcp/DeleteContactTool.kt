// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.DeleteContactUseCase
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.TwoStepDelete
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** `delete_contact`: deletes one contact with all its personal data; confirmed by the user. */
@Component
class DeleteContactTool(
    private val deleteContact: DeleteContactUseCase,
) : McpTool {
    override val name = "delete_contact"
    override val readOnly = false
    override val description =
        "Delete one contact by its id, with its channels and links to applications and interviews. The user " +
            "is asked to confirm in their MCP client first; nothing is deleted without their yes. " +
            "Answers status deleted or declined."
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
            { (it as? ContactResult.Unconfirmed)?.outcome },
            ::answer,
        )
    }

    private fun answer(result: ContactResult<Unit>): ToolAnswer =
        when (result) {
            is ContactResult.Success -> ToolAnswer.Result(Unit)
            ContactResult.NotFound -> ToolAnswer.Error("not-found", "No contact has this id.")
            is ContactResult.StorageFailure -> ToolAnswer.Error("unavailable", "The delete cannot run now.")
            else -> ToolAnswer.Error("failed", "The delete could not be completed.")
        }

    // A named method, not the lambda above: the architecture rule sees the one use case call here.
    private fun delete(
        id: UUID,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): ContactResult<Unit> = deleteContact.execute(ContactId(id), requester, token)
}
