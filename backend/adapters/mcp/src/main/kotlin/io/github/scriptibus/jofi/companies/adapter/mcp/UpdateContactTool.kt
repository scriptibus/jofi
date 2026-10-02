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
        "Replace ALL details of a contact, channels included: a field or channel left out is removed, so call " +
            "get_contact first, change what you mean to change and send everything back (the fields of `contact` " +
            "in the answer, `null` for a field that is not set) with the `version` you read. A stale version " +
            "answers version-conflict and changes nothing. A value that shows [withheld] is hidden from you; " +
            "sending it back is refused (withheld-value), so such a contact cannot be updated here."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "name"],
          "properties": {
            "id": {"type": "string", "format": "uuid"},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_contact."},
            ${ContactToolInput.PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldPath()?.let { return ToolProblems.withheldValue(it) }
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = update(call, id, version)) {
            is ContactResult.Success -> ToolAnswer.Result(ContactDetailResult.from(result.value))
            is ContactResult.Failure -> ContactToolErrors.failure(result)
        }
    }

    private fun update(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = updateContact.execute(ContactId(id), ContactToolInput.of(call.arguments), version, call.caller)
}
