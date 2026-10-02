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
        "Replace ALL details of a job application. Call get_application first, change what you mean to change " +
            "and send it back with the same keys and nesting: `id`, `version`, `companyId` and the typed fields " +
            "as they are, and the `content` of `posting`, `notes` and `languageAndTone` (not the wrapper). Do not " +
            "send `readOnly` (status, contacts, scores, sources, decline reason): no tool here changes it. Every " +
            "property is required: leaving one out is refused, only an explicit `null` clears a field. A stale " +
            "version answers version-conflict and changes nothing. A value that shows [withheld] is hidden from " +
            "you; sending it back is refused (withheld-value) and names the argument, so an application with a " +
            "hidden value cannot be updated through this tool. Answers the application."
    override val inputSchema = ApplicationToolSchema.schema(update = true)

    override fun call(call: ToolCall): ToolAnswer {
        call.arguments.withheldPath()?.let { return ToolProblems.withheldValue(it) }
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
