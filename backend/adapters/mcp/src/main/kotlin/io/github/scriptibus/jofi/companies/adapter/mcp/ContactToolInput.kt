// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.adapter.mcp

import io.github.scriptibus.jofi.companies.domain.ChannelInput
import io.github.scriptibus.jofi.companies.domain.ChannelKind
import io.github.scriptibus.jofi.companies.domain.CompanyId
import io.github.scriptibus.jofi.companies.domain.ContactInput
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments

/** The contact fields `create_contact` and `update_contact` share: arguments in, the domain's input out. */
internal object ContactToolInput {
    /** A missing name is an empty one, which the domain's validation reports as `required`. */
    fun of(arguments: ToolArguments) =
        ContactInput(
            name = arguments.text("name").orEmpty(),
            role = arguments.text("role"),
            company = arguments.uuid("companyId")?.let(::CompanyId),
            channels = arguments.objects("channels").map(::channelOf),
            relationshipNotes = arguments.text("relationshipNotes"),
        )

    private fun channelOf(channel: ToolArguments) =
        ChannelInput(
            kind = channel.enum("kind", ChannelKind::class.java) ?: throw InvalidToolArgument("channels"),
            value = channel.text("value") ?: throw InvalidToolArgument("channels"),
            label = channel.text("label"),
        )

    private val KINDS = enumValues<ChannelKind>().joinToString(", ", "[", "]") { "\"${it.name}\"" }

    /** The JSON Schema properties of the fields above, to be spliced into a tool's schema. */
    val PROPERTIES =
        """
        "name": {"type": "string", "description": "The contact's name."},
        "role": {"type": ["string", "null"], "description": "For example Recruiter."},
        "companyId": {"type": ["string", "null"], "format": "uuid", "description": "The company the contact works for."},
        "channels": {
          "type": ["array", "null"],
          "description": "Ways to reach the contact.",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["kind", "value"],
            "properties": {
              "kind": {"enum": $KINDS},
              "value": {"type": "string"},
              "label": {"type": ["string", "null"], "description": "For example work or mobile."}
            }
          }
        },
        "relationshipNotes": {"type": ["string", "null"], "description": "Notes on the relationship, Markdown."}
        """.trimIndent()
}
