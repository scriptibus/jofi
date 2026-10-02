// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.UpdateApplicationUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolProblems
import org.springframework.stereotype.Component
import java.util.UUID

/** `update_application`: replaces all details of an application, based on the version the caller read. */
@Component
class UpdateApplicationTool(
    private val updateApplication: UpdateApplicationUseCase,
) : McpTool {
    override val name = "update_application"
    override val readOnly = false
    override val description =
        "Replace ALL details of a job application: a field left out is cleared, so call get_application first, " +
            "change what you mean to change and send everything back with the `version` you read: `companyId`, " +
            "`title` and `location` (in `posting`), `portalNotes` (in `notes`), the typed fields as they are, and " +
            "`null` for what is not set; the other texts of `notes` go to `payBand.estimateBasis` and " +
            "`offer.bonus`, `offer.benefits`, `offer.noticePeriod`. The status, contacts, scores and unread flag " +
            "are not changed here. A stale version answers version-conflict and changes nothing. A value that " +
            "shows [withheld] is hidden from you; sending it back is refused (withheld-value). Answers the " +
            "application."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["id", "version", "companyId", "title"],
          "properties": {
            "id": {"type": "string", "format": "uuid"},
            "version": {"type": "integer", "minimum": 0, "description": "The version from get_application."},
            ${ApplicationToolInput.PROPERTIES}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldArgument()?.let { return ToolProblems.withheldValue(it) }
        val id = call.arguments.uuid("id") ?: throw InvalidToolArgument("id")
        val version = call.arguments.long("version") ?: throw InvalidToolArgument("version")
        return when (val result = update(call, id, version)) {
            is ApplicationResult.Success -> ToolAnswer.Result(ApplicationDetailResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }

    private fun update(
        call: ToolCall,
        id: UUID,
        version: Long,
    ) = updateApplication.execute(ApplicationId(id), ApplicationToolInput.of(call.arguments), version, call.caller)
}
