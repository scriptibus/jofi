// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.ContactRef
import io.github.scriptibus.jofi.applications.domain.InterviewInput
import io.github.scriptibus.jofi.applications.domain.InterviewOutcome
import io.github.scriptibus.jofi.applications.domain.InterviewType
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments
import java.util.UUID

/**
 * The interview fields `log_interview` and `update_interview` share: arguments in, the domain's input out. The names
 * and nesting are those of the interview results (the content of `interview` under the same key), so a result
 * goes back without reshaping. In `update_interview` every property is required, `null` for "not set" (only an
 * explicit `null` clears); in `log_interview` the optional ones may be left out.
 */
internal object InterviewToolInput {
    /** The time zone is validated by the domain (an IANA id or an offset), the start by [ToolArguments]. */
    fun of(arguments: ToolArguments) =
        InterviewInput(
            type = arguments.enum("type", InterviewType::class.java) ?: throw InvalidToolArgument("type"),
            localStart = arguments.localDateTime("localStart") ?: throw InvalidToolArgument("localStart"),
            timeZone = arguments.text("timeZone").orEmpty(),
            participants = arguments.uuids("participantIds").map(::ContactRef).toSet(),
            preparationNotes = arguments.obj("interview")?.text("preparationNotes"),
            notes = arguments.obj("interview")?.text("notes"),
            outcome = arguments.enum("outcome", InterviewOutcome::class.java),
        )

    /** A required id argument; a missing one is reported as that argument. */
    fun id(
        arguments: ToolArguments,
        name: String,
    ): UUID = arguments.uuid(name) ?: throw InvalidToolArgument(name)

    fun version(arguments: ToolArguments): Long = arguments.long("version") ?: throw InvalidToolArgument("version")

    // Bounds against oversized payloads, twice what the domain accepts: the domain reports its own limits.
    private const val MAX_NOTES = 100_000
    private const val MAX_PARTICIPANTS = 40
    private const val MAX_START = 32
    private const val MAX_ZONE = 64

    private inline fun <reified E : Enum<E>> names(nullable: Boolean): String =
        enumValues<E>().joinToString(", ", "[", if (nullable) ", null]" else "]") { "\"${it.name}\"" }

    /** The JSON Schema of `log_interview` ([update] false) or `update_interview` (true). */
    fun schema(update: Boolean): String {
        val common = listOf("type", "localStart", "timeZone")
        val required =
            if (update) {
                listOf("applicationId", "id", "version") + common + listOf("participantIds", "outcome", "interview")
            } else {
                listOf("applicationId") + common
            }
        return """
            {
              "type": "object",
              "additionalProperties": false,
              "required": [${required.joinToString(", ") { "\"$it\"" }}],
              "properties": {${properties(update)}}
            }
            """.trimIndent()
    }

    private fun properties(update: Boolean): String =
        """
        "applicationId": {"type": "string", "format": "uuid"},
        ${if (update) IDENTITY else ""}
        "type": {"enum": ${names<InterviewType>(nullable = false)}},
        "localStart": {
          "type": "string",
          "maxLength": $MAX_START,
          "description": "When it starts on the clocks of timeZone, such as 2026-10-05T10:00."
        },
        "timeZone": {
          "type": "string",
          "maxLength": $MAX_ZONE,
          "description": "The zone the time was agreed in: an IANA id such as Europe/Berlin or an offset such as +02:00."
        },
        "participantIds": {
          "type": ["array", "null"],
          "maxItems": $MAX_PARTICIPANTS,
          "items": {"type": "string", "format": "uuid"},
          "description": "Contacts who took part, at most 20; an empty list or null for none."
        },
        "outcome": {"enum": ${names<InterviewOutcome>(nullable = true)}},
        "interview": ${interview(update)}
        """.trimIndent()

    private fun interview(update: Boolean): String =
        """
        {
          "type": ${if (update) "\"object\"" else "[\"object\", \"null\"]"},
          "additionalProperties": false,
          ${if (update) """"required": ["preparationNotes", "notes"],""" else ""}
          "properties": {
            "preparationNotes": {"type": ["string", "null"], "maxLength": $MAX_NOTES, "description": "Markdown."},
            "notes": {"type": ["string", "null"], "maxLength": $MAX_NOTES, "description": "The user's notes afterwards."}
          }
        }
        """.trimIndent()

    private const val IDENTITY =
        """"id": {"type": "string", "format": "uuid"}, "version": {"type": "integer", "minimum": 0},"""
}
