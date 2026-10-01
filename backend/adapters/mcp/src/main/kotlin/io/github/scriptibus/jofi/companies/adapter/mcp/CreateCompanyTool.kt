// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.CreateCompanyUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `create_company`: adds a company; the changelog records the caller as the actor. */
@Component
class CreateCompanyTool(
    private val createCompany: CreateCompanyUseCase,
) : McpTool {
    override val name = "create_company"
    override val readOnly = false
    override val description =
        "Add a company the user applies to or watches. Only `name` is required. Search first " +
            "(search_companies) so the same company is not added twice. Answers the new company with its id."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["name"],
          "properties": {${CompanyToolInput.PROPERTIES}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = create(call)) {
            is CompanyResult.Success -> ToolAnswer.Result(CompanyDetailResult.from(result.value))
            is CompanyResult.Failure -> CompanyToolErrors.failure(result)
        }

    private fun create(call: ToolCall) = createCompany.execute(CompanyToolInput.of(call.arguments), call.caller)
}
