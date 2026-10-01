// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.application.SearchApplicationsUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSearchInput
import io.github.scriptibus.jofi.applications.domain.ApplicationSortKey
import io.github.scriptibus.jofi.applications.domain.ApplicationStatus
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.SearchValidation
import io.github.scriptibus.jofi.applications.domain.SortDirection
import io.github.scriptibus.jofi.applications.domain.SourceKind
import io.github.scriptibus.jofi.shared.adapter.mcp.McpTool
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolAnswer
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolCall
import org.springframework.stereotype.Component

/** `search_applications`: one page of the application list with filters and order (spec §6.3), read only. */
@Component
class SearchApplicationsTool(
    private val searchApplications: SearchApplicationsUseCase,
) : McpTool {
    override val name = "search_applications"
    override val readOnly = true
    override val description =
        "Search the user's job applications. All filters are optional and combine with AND; a list matches any " +
            "of its values. `text` matches titles fuzzily. Returns one page (newest change first unless `sort` " +
            "is set) with the total count. Titles and locations come from job postings and are marked untrusted."
    override val inputSchema = SCHEMA

    override fun call(call: ToolCall): ToolAnswer =
        when (val validation = inputOf(call.arguments).validate()) {
            is SearchValidation.Invalid -> {
                ApplicationToolErrors.invalidSearch(validation.violations)
            }

            is SearchValidation.Valid -> {
                val search = validation.search
                when (val result = searchApplications.execute(search)) {
                    is ApplicationResult.Success -> {
                        ToolAnswer.Result(ApplicationSearchResult.from(result.value, search.page, search.size))
                    }

                    is ApplicationResult.Failure -> {
                        ApplicationToolErrors.failure(result)
                    }
                }
            }
        }

    private fun inputOf(arguments: ToolArguments) =
        ApplicationSearchInput(
            text = arguments.text("text"),
            company = arguments.uuid("companyId")?.let(::CompanyRef),
            contact = arguments.uuid("contactId")?.let(::ContactRef),
            statuses = arguments.enums("statuses", ApplicationStatus::class.java),
            unread = arguments.bool("unread"),
            languages = arguments.texts("languages"),
            sourceKinds = arguments.enums("sourceKinds", SourceKind::class.java),
            createdFrom = arguments.instant("createdFrom"),
            createdTo = arguments.instant("createdTo"),
            updatedFrom = arguments.instant("updatedFrom"),
            updatedTo = arguments.instant("updatedTo"),
            sort = arguments.enum("sort", ApplicationSortKey::class.java),
            direction = arguments.enum("direction", SortDirection::class.java),
            page = arguments.int("page") ?: 0,
            size = arguments.int("size") ?: DEFAULT_SIZE,
        )

    private companion object {
        /** Smaller than the UI's page: every result goes into the model's context. */
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50

        val SCHEMA =
            """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "text": {"type": "string", "description": "Words of the job title, matched fuzzily."},
                "companyId": {"type": "string", "format": "uuid"},
                "contactId": {"type": "string", "format": "uuid", "description": "A linked contact person."},
                "statuses": {"type": "array", "items": {"enum": ${names<ApplicationStatus>()}}},
                "unread": {"type": "boolean"},
                "languages": {"type": "array", "items": {"type": "string"},
                  "description": "Application languages as BCP 47 tags; \"de\" also matches \"de-CH\"."},
                "sourceKinds": {"type": "array", "items": {"enum": ${names<SourceKind>()}}},
                "createdFrom": {"type": "string", "format": "date-time", "description": "Inclusive."},
                "createdTo": {"type": "string", "format": "date-time", "description": "Exclusive."},
                "updatedFrom": {"type": "string", "format": "date-time", "description": "Inclusive."},
                "updatedTo": {"type": "string", "format": "date-time", "description": "Exclusive."},
                "sort": {"enum": ${names<ApplicationSortKey>()}},
                "direction": {"enum": ${names<SortDirection>()}},
                "page": {"type": "integer", "minimum": 0, "default": 0},
                "size": {"type": "integer", "minimum": 1, "maximum": $MAX_SIZE, "default": $DEFAULT_SIZE}
              }
            }
            """.trimIndent()

        private inline fun <reified E : Enum<E>> names(): String =
            enumValues<E>().joinToString(", ", "[", "]") { "\"${it.name}\"" }
    }
}
