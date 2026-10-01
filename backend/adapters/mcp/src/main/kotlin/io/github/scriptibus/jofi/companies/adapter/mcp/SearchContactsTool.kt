// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.SearchContactsUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.ContactResult
import io.github.scriptibus.jofi.companies.domain.ContactSearch
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `search_contacts`: one page of the user's contact persons, by fuzzy name and company; read only. */
@Component
class SearchContactsTool(
    private val searchContacts: SearchContactsUseCase,
) : McpTool {
    override val name = "search_contacts"
    override val readOnly = true
    override val description =
        "Search the user's contact persons (recruiters, hiring managers, referrers). `text` matches names " +
            "fuzzily (best match first); `companyId` keeps the contacts of one company. Returns one page with " +
            "the total count; names and roles are marked untrusted. get_contact has the channels."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "text": {"type": "string", "maxLength": ${SearchLimits.MAX_TEXT}, "description": "Words of the name."},
            "companyId": {"type": "string", "format": "uuid"},
            "page": {"type": "integer", "minimum": 0, "default": 0},
            ${SearchLimits.SIZE_PROPERTY}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val page = call.arguments.int("page") ?: 0
        val size = call.arguments.int("size") ?: SearchLimits.DEFAULT_SIZE
        val search =
            ContactSearch.of(
                call.arguments.text("text"),
                call.arguments.uuid("companyId")?.let(::CompanyId),
                page,
                size,
            ) ?: return outOfRange(page, size)
        return when (val result = searchContacts.execute(search)) {
            is ContactResult.Success -> ToolAnswer.Result(ContactSearchResult.from(result.value, page, size))
            is ContactResult.Failure -> ContactToolErrors.failure(result)
        }
    }

    private fun outOfRange(
        page: Int,
        size: Int,
    ) = ToolAnswer.Error(
        "invalid-arguments",
        "The search arguments are invalid.",
        listOfNotNull(
            ArgumentProblem("page", "out-of-range").takeIf { page < 0 },
            ArgumentProblem("size", "out-of-range").takeIf { size !in 1..SearchLimits.MAX_SIZE },
        ),
    )
}
