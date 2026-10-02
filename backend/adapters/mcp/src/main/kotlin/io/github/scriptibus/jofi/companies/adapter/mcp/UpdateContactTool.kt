// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.UpdateContactUseCase
import io.github.scriptibus.jofi.companies.domain.ContactId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component
import java.util.UUID

/** `update_contact`: replaces all details of a contact, channels included, based on the version read. */
@Component
class UpdateContactTool(
    private val updateContact: UpdateContactUseCase,
) : McpTool {
    override val name = "update_contact"
    override val readOnly = false
    override val description =
        "Replace ALL details of a contact, channels included. Call get_contact first, change what you mean to " +
            "change and send it back with the same keys: `id`, `version`, `companyId` and `contact`, the `content` " +
            "of the answer's `contact` (not the wrapper). Do not send `readOnly` (timestamps). Every property is " +
            "required: leaving one out is refused, only an explicit `null` (or `[]` for `channels`) clears it. A " +
            "stale version answers version-conflict and changes nothing. A value that shows [withheld] is hidden " +
            "from you; sending it back is refused (withheld-value) and names the argument, so such a contact " +
            "cannot be updated here. Answers the contact."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "companyId", "contact"],
          "properties": {
            "id": {"type": "string", "format": "uuid"},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_contact."},
            ${ContactToolInput.COMPANY_PROPERTY},
            "contact": ${ContactToolInput.UPDATE_OBJECT}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldPath()?.let { return ToolProblems.withheldValue(it) }
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = update(call, id, version)) {
            is ContactResult.Success -> ToolAnswer.Result(ContactDetailResult.from(result.value))
            is ContactResult.Failure -> ContactToolErrors.failure(result, "contact.")
        }
    }

    private fun update(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = updateContact.execute(ContactId(id), ContactToolInput.ofUpdate(call.arguments), version, call.caller)
}
