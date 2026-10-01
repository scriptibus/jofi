// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.GetContactUseCase
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `get_contact`: one contact person with the channels, by id; read only. */
@Component
class GetContactTool(
    private val getContact: GetContactUseCase,
) : McpTool {
    override val name = "get_contact"
    override val readOnly = true
    override val description =
        "Get one contact person by its id (from search_contacts): role, company, channels, the user's notes and " +
            "the version `update_contact` must be based on. Name, role and channels are marked untrusted."
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
        return when (val result = getContact.execute(ContactId(id))) {
            is ContactResult.Success -> ToolAnswer.Result(ContactDetailResult.from(result.value))
            is ContactResult.Failure -> ContactToolErrors.failure(result)
        }
    }
}
