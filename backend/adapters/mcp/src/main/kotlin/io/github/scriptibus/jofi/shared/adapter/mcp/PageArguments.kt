// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.github.scriptibus.jofi.shared.domain.paging.PageInput
import io.github.scriptibus.jofi.shared.domain.paging.PageRequest

/**
 * The `page` and `size` arguments of the list tools (ADR-0056). The limits are the domain's: the schema only
 * mirrors them so a client sees them, and the use case checks them again.
 */
object PageArguments {
    fun of(arguments: ToolArguments) = PageInput(arguments.int("page"), arguments.int("size"))

    /** Two schema properties, to be put in a tool's `properties` next to its own. */
    val SCHEMA_PROPERTIES =
        """
        "page": {"type": "integer", "minimum": 0, "maximum": ${PageRequest.MAX_PAGE}, "default": 0,
          "description": "Page number from 0. Ask for the next one while the answer's hasMore is true."},
        "size": {"type": "integer", "minimum": 1, "maximum": ${PageRequest.MAX_SIZE},
          "default": ${PageRequest.DEFAULT_SIZE}, "description": "Entries per page."}
        """.trimIndent()
}
