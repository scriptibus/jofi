// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.tasks.adapter.mcp

import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import io.github.scriptibus.jofi.tasks.domain.ApplicationRef
import io.github.scriptibus.jofi.tasks.domain.CompanyRef
import io.github.scriptibus.jofi.tasks.domain.ContactRef
import io.github.scriptibus.jofi.tasks.domain.TaskInput
import io.github.scriptibus.jofi.tasks.domain.TaskLink
import io.github.scriptibus.jofi.tasks.domain.TaskTimingInput
import io.github.scriptibus.jofi.tasks.domain.TimeBucket

/** The arguments of `create_task`: arguments in, the domain's input out. */
internal object TaskToolInput {
    /** A missing title or time zone is an empty one, which the domain's validation reports. */
    fun of(arguments: ToolArguments) =
        TaskInput(
            title = arguments.text("title").orEmpty(),
            timing =
                TaskTimingInput(
                    timeZone = arguments.text("timeZone").orEmpty(),
                    bucket = arguments.enum("bucket", TimeBucket::class.java),
                    localDue = arguments.localDateTime("localDue"),
                ),
            link = arguments.obj("link")?.let(::linkOf),
            notes = arguments.text("notes"),
        )

    /** Whatever is wrong inside the link object is reported as the argument `link`. */
    private fun linkOf(link: ToolArguments): TaskLink =
        try {
            val id = link.uuid("id") ?: throw InvalidToolArgument("link")
            when (link.enum("type", TaskLinkKind::class.java) ?: throw InvalidToolArgument("link")) {
                TaskLinkKind.APPLICATION -> ApplicationRef(id)
                TaskLinkKind.COMPANY -> CompanyRef(id)
                TaskLinkKind.CONTACT -> ContactRef(id)
            }
        } catch (_: InvalidToolArgument) {
            throw InvalidToolArgument("link")
        }

    // Bounds against oversized payloads, twice what the domain accepts: the domain reports its own limits.
    const val MAX_TITLE = 600
    const val MAX_NOTES = 20_000

    private val BUCKETS = enumValues<TimeBucket>().joinToString(", ", "[", ", null]") { "\"${it.name}\"" }
    private val LINK_TYPES = enumValues<TaskLinkKind>().joinToString(", ", "[", "]") { "\"${it.name}\"" }

    /** The JSON Schema properties of `create_task`. */
    val PROPERTIES =
        """
        "title": {"type": "string", "maxLength": $MAX_TITLE, "description": "What to do."},
        "timeZone": {
          "type": "string",
          "maxLength": 64,
          "description": "The user's time zone, an IANA id such as Europe/Berlin or an offset such as +02:00."
        },
        "bucket": {
          "enum": $BUCKETS,
          "description": "A rough time relative to today in timeZone. Give this or localDue, not both."
        },
        "localDue": {
          "type": ["string", "null"],
          "maxLength": 32,
          "description": "An exact wall-clock time in timeZone, such as 2026-10-05T10:00. Give this or bucket."
        },
        "link": {
          "type": ["object", "null"],
          "additionalProperties": false,
          "required": ["type", "id"],
          "description": "What the task is about.",
          "properties": {"type": {"enum": $LINK_TYPES}, "id": {"type": "string", "format": "uuid"}}
        },
        "notes": {"type": ["string", "null"], "maxLength": $MAX_NOTES, "description": "Notes, Markdown."}
        """.trimIndent()
}
