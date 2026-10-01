// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.domain.CompanyInput
import io.github.scriptibus.jofi.companies.domain.CompanySize
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments

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

    val SIZES = enumValues<CompanySize>().joinToString(", ", "[", "]") { "\"${it.name}\"" }

    /** The JSON Schema properties of the fields above, to be spliced into a tool's schema. */
    val PROPERTIES =
        """
        "name": {"type": "string", "description": "The company's name."},
        "website": {"type": "string", "description": "An absolute http(s) URL."},
        "industry": {"type": "string"},
        "size": {"enum": $SIZES, "description": "Head-count band."},
        "locations": {"type": "array", "items": {"type": "string"}, "description": "Cities, regions or remote."},
        "careersPage": {"type": "string", "description": "An absolute http(s) URL."},
        "researchNotes": {"type": "string", "description": "The user's notes, Markdown."}
        """.trimIndent()
}
