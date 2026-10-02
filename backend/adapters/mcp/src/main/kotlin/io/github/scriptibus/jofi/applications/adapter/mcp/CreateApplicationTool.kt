// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.CreateApplicationUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `create_application`: adds an application in its first status; the changelog records the caller as the actor. */
@Component
class CreateApplicationTool(
    private val createApplication: CreateApplicationUseCase,
) : McpTool {
    override val name = "create_application"
    override val readOnly = false
    override val description =
        "Add a job application by hand. Required: `companyId` (an existing company: find it with " +
            "search_companies or create it first) and `title`. Everything else is optional and may be null. " +
            "Answers the new application with its id and version, in the same shape as get_application: the " +
            "posting's title and location and the notes are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["companyId", "title"],
          "properties": {${ApplicationToolInput.PROPERTIES}}
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer =
        when (val result = create(call)) {
            is ApplicationResult.Success -> ToolAnswer.Result(ApplicationDetailResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }

    // A named method, not a lambda: the architecture rule sees the one use case call here.
    private fun create(call: ToolCall) = createApplication.execute(ApplicationToolInput.of(call.arguments), call.caller)
}
