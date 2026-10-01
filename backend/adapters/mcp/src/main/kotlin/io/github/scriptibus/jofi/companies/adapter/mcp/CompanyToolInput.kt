// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments

/** What the search tools of companies and contacts allow; every result goes into the model's context. */
internal object SearchLimits {
    const val DEFAULT_SIZE = 20

    /** As `search_applications`; the REST list's 200 would put 76 KB of results into one context. */
    const val MAX_SIZE = 50

    /** The longest name the domain stores (200), so a longer search text cannot match anything. */
    const val MAX_TEXT = 200

    const val SIZE_PROPERTY =
        """"size": {"type": "integer", "minimum": 1, "maximum": $MAX_SIZE, "default": $DEFAULT_SIZE}"""
}

/** The company fields `create_company` and `update_company` share: arguments in, the domain's input out. */
internal object CompanyToolInput {
    /** A missing name is an empty one, which the domain's validation reports as `required`. */
    fun of(arguments: ToolArguments) =
        CompanyInput(
            name = arguments.text("name").orEmpty(),
            website = arguments.text("website"),
            industry = arguments.text("industry"),
            size = arguments.enum("size", CompanySize::class.java),
            locations = arguments.texts("locations"),
            careersPage = arguments.text("careersPage"),
            researchNotes = arguments.text("researchNotes"),
        )

    /** The sizes and `null`: `get_company` answers `null` for a size that is not set, and sends it back. */
    val SIZES = enumValues<CompanySize>().joinToString(", ", "[", ", null]") { "\"${it.name}\"" }

    /**
     * The JSON Schema properties of the fields above, to be spliced into a tool's schema. Optional ones accept
     * `null` as "not set", because `get_company` answers `null` and `update_company` takes the answer back.
     */
    val PROPERTIES =
        """
        "name": {"type": "string", "description": "The company's name."},
        "website": {"type": ["string", "null"], "description": "An absolute http(s) URL."},
        "industry": {"type": ["string", "null"]},
        "size": {"enum": $SIZES, "description": "Head-count band."},
        "locations": {"type": ["array", "null"], "items": {"type": "string"}, "description": "Cities, regions or remote."},
        "careersPage": {"type": ["string", "null"], "description": "An absolute http(s) URL."},
        "researchNotes": {"type": ["string", "null"], "description": "Notes about the company, Markdown."}
        """.trimIndent()
}
