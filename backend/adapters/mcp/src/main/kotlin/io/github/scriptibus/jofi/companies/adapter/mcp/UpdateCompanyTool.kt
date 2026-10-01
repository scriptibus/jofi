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
        "Replace ALL details of a company: a field left out is cleared, so call get_company first, change what " +
            "you mean to change and send everything back with the `version` you read. A stale version answers " +
            "version-conflict and changes nothing. The preference is not changed here."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "name"],
          "properties": {
            "id": {"type": "string", "format": "uuid"},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_company."},
            ${CompanyToolInput.PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = update(call, id, version)) {
            is CompanyResult.Success -> ToolAnswer.Result(CompanyDetailResult.from(result.value))
            is CompanyResult.Failure -> CompanyToolErrors.failure(result)
        }
    }

    private fun update(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = updateCompany.execute(CompanyId(id), CompanyToolInput.of(call.arguments), version, call.caller)
}
