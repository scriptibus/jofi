// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.UpdateCompanyUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component
import java.util.UUID

/** `update_company`: replaces all details of a company, based on the version the caller read. */
@Component
class UpdateCompanyTool(
    private val updateCompany: UpdateCompanyUseCase,
) : McpTool {
    override val name = "update_company"
    override val readOnly = false
    override val description =
        "Replace ALL details of a company. Call get_company first, change what you mean to change and send it " +
            "back with the same keys: `id`, `version` and `company`, the `content` of the answer's `company` (not " +
            "the wrapper). Do not send `readOnly` (application count, preference, timestamps): no tool here " +
            "changes it. Every field of `company` is required: leaving one out is refused, only an explicit " +
            "`null` (or `[]` for `locations`) clears it. A stale version answers version-conflict and changes " +
            "nothing. A value that shows [withheld] is hidden from you; sending it back is refused " +
            "(withheld-value) and names the argument, so such a company cannot be updated here. Answers the company."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "company"],
          "properties": {
            "id": {"type": "string", "format": "uuid"},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_company."},
            "company": ${CompanyToolInput.UPDATE_OBJECT}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldPath()?.let { return ToolProblems.withheldValue(it) }
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = update(call, id, version)) {
            is CompanyResult.Success -> ToolAnswer.Result(CompanyDetailResult.from(result.value))
            is CompanyResult.Failure -> CompanyToolErrors.failure(result, "company.")
        }
    }

    private fun update(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = updateCompany.execute(CompanyId(id), CompanyToolInput.ofUpdate(call.arguments), version, call.caller)
}
