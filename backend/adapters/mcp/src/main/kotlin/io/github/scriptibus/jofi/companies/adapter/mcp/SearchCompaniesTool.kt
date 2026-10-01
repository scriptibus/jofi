// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.application.SearchCompaniesUseCase
import io.github.scriptibus.jofi.companies.domain.CompanyResult
import io.github.scriptibus.jofi.companies.domain.CompanySearch
import io.github.scriptibus.jofi.companies.domain.PreferenceKind
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `search_companies`: one page of the user's companies, by fuzzy name and preference; read only. */
@Component
class SearchCompaniesTool(
    private val searchCompanies: SearchCompaniesUseCase,
) : McpTool {
    override val name = "search_companies"
    override val readOnly = true
    override val description =
        "Search the user's companies. `text` matches names fuzzily (best match first); `preference` keeps " +
            "favourites or blacklisted ones. Returns one page with the total count and each company's number of " +
            "applications. Names and other facts may come from job postings and are marked untrusted."
    override val inputSchema =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "text": {"type": "string", "maxLength": ${SearchLimits.MAX_TEXT}, "description": "Words of the name."},
            "preference": {"enum": ${PreferenceKind.entries.joinToString(", ", "[", "]") { "\"${it.name}\"" }}},
            "page": {"type": "integer", "minimum": 0, "default": 0},
            ${SearchLimits.SIZE_PROPERTY}
          }
        }
        """.trimIndent()

    override fun call(call: ToolCall): ToolAnswer {
        val page = call.arguments.int("page") ?: 0
        val size = call.arguments.int("size") ?: SearchLimits.DEFAULT_SIZE
        val search =
            CompanySearch.of(
                call.arguments.text("text"),
                call.arguments.enum("preference", PreferenceKind::class.java),
                page,
                size,
            ) ?: return outOfRange(page, size)
        return when (val result = searchCompanies.execute(search)) {
            is CompanyResult.Success -> ToolAnswer.Result(CompanySearchResult.from(result.value, page, size))
            is CompanyResult.Failure -> CompanyToolErrors.failure(result)
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
