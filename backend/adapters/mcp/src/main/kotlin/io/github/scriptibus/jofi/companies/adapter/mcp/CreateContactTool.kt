// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.CreateContactUseCase
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component

/** `create_contact`: adds a contact person; the changelog records the caller as the actor. */
@Component
class CreateContactTool(
    private val createContact: CreateContactUseCase,
) : McpTool {
    override val name = "create_contact"
    override val readOnly = false
    override val description =
        "Add a contact person, optionally at a company and with channels (email, phone, web, other). Only " +
            "`name` is required. Search first (search_contacts) so the same person is not added twice. A value " +
            "that shows [withheld] is hidden from you and refused (withheld-value). Answers the new contact with " +
            "its id; link it to an application with set_application_contacts."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["name"],
          "properties": {${ContactToolInput.PROPERTIES}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        call.arguments.withheldPath()?.let(ToolProblems::withheldValue)
            ?: when (val result = create(call)) {
                is ContactResult.Success -> ToolAnswer.Result(ContactDetailResult.from(result.value))
                is ContactResult.Failure -> ContactToolErrors.failure(result)
            }

    private fun create(call: ToolCall) = createContact.execute(ContactToolInput.of(call.arguments), call.caller)
}
