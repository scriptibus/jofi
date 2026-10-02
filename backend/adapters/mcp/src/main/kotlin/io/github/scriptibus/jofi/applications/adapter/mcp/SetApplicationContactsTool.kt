// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.LinkApplicationContactsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `set_application_contacts`: links exactly the given contacts to an application. The use case replaces the
 * linked set (linking and unlinking are both "read, change the set, send it back with the version"), so this
 * one tool is how a contact is linked and unlinked.
 */
@Component
class SetApplicationContactsTool(
    private val linkContacts: LinkApplicationContactsUseCase,
) : McpTool {
    override val name = "set_application_contacts"
    override val readOnly = false
    override val description =
        "Link contacts to a job application: `contactIds` becomes EXACTLY the set of linked contacts, so to link " +
            "one more, call get_application, add its id to `contactIds` and send the `version` you read; to " +
            "unlink one, leave its id out. An empty list unlinks all. A stale version answers version-conflict " +
            "and changes nothing. Answers the application."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "contactIds"],
          "properties": {
            "id": {"type": "string", "format": "uuid", "description": "The application."},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_application."},
            "contactIds": {"type": "array", "items": {"type": "string", "format": "uuid"}}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        val contacts =
            call.arguments
                .uuids("contactIds")
                .map(::ContactRef)
                .toSet()
        return when (val result = link(call, id, contacts, version)) {
            is ApplicationResult.Success -> ToolAnswer.Result(ApplicationDetailResult.from(result.value))
            is ApplicationResult.Invalid -> invalid(result)
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }

    private fun link(
        call: ToolCall,
        id: UUID,
        contacts: Set<ContactRef>,
        version: Long,
    ) = linkContacts.execute(ApplicationId(id), contacts, version, call.caller)

    /** The use case only reports the contacts: too many, or one that does not exist. */
    private fun invalid(result: ApplicationResult.Invalid) =
        ToolAnswer.Error(
            "invalid-arguments",
            "The contacts cannot be linked.",
            result.violations.map { ArgumentProblem("contactIds", ToolProblems.problemCode(it.problem.name)) },
        )
}
