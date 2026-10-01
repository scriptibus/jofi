// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.DeleteCompanyUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.TwoStepDelete
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationRequester
import io.github.scriptibus.jofi.shared.domain.confirmation.ConfirmationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** `delete_company`: deletes one company without applications, with its contacts; confirmed by the user. */
@Component
class DeleteCompanyTool(
    private val deleteCompany: DeleteCompanyUseCase,
) : McpTool {
    override val name = "delete_company"
    override val readOnly = false
    override val description =
        "Delete one company by its id, together with its contacts. A company that still has applications " +
            "cannot be deleted. The user is asked to confirm in their MCP client first; nothing is deleted " +
            "without their yes. Answers status deleted or declined."
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
            { (it as? CompanyResult.Unconfirmed)?.outcome },
            ::answer,
        )
    }

    private fun answer(result: CompanyResult<Unit>): ToolAnswer =
        when (result) {
            is CompanyResult.Success -> {
                ToolAnswer.Result(Unit)
            }

            CompanyResult.NotFound -> {
                ToolAnswer.Error("not-found", "No company has this id.")
            }

            CompanyResult.HasApplications -> {
                ToolAnswer.Error("has-applications", "The company still has applications; delete those first.")
            }

            is CompanyResult.StorageFailure -> {
                ToolAnswer.Error("unavailable", "The delete cannot run now.")
            }

            else -> {
                ToolAnswer.Error("failed", "The delete could not be completed.")
            }
        }

    // A named method, not the lambda above: the architecture rule sees the one use case call here.
    private fun delete(
        id: UUID,
        requester: ConfirmationRequester,
        token: ConfirmationToken?,
    ): CompanyResult<Unit> = deleteCompany.execute(CompanyId(id), requester, token)
}
