// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.GetPostingImportUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `get_import_status`: one posting import for polling, read only; it fetches nothing and waits for nothing. */
@Component
class GetImportStatusTool(
    private val getPostingImport: GetPostingImportUseCase,
) : McpTool {
    override val name = "get_import_status"
    override val readOnly = true
    override val description =
        "Get the status of a posting import by the `id` start_text_import answered: PENDING " +
            "(the AI is still reading the posting; ask again in a few seconds), SUCCEEDED (`applicationId` names " +
            "the new application: read it with get_application) or FAILED (`failure` says why, such as " +
            "NOT_A_POSTING or AI_UNAVAILABLE; the user can retry in the app)."
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
        return when (val result = getPostingImport.execute(ImportId(id))) {
            is ApplicationResult.Success -> ToolAnswer.Result(PostingImportResult.from(result.value))
            is ApplicationResult.Failure -> ApplicationToolErrors.failure(result)
        }
    }
}
