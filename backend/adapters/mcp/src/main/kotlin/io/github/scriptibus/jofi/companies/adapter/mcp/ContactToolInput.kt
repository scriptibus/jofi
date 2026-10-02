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

    /** `update_contact` takes the contact's fields as `get_contact` returns them: inside the `contact` object. */
    fun ofUpdate(arguments: ToolArguments): ContactInput {
        val contact = arguments.obj("contact") ?: throw InvalidToolArgument("contact")
        return of(contact).copy(company = arguments.uuid("companyId")?.let(::CompanyId))
    }

    private val KINDS = enumValues<ChannelKind>().joinToString(", ", "[", "]") { "\"${it.name}\"" }

    /**
     * A channel: in an update `label` is required too, `null` for none. A blank text would clear like `null` (the
     * domain drops a channel without a value), so an update's texts must hold a visible character.
     */
    private fun channels(update: Boolean): String {
        val text = if (update) CompanyToolInput.NOT_BLANK else ""
        val description = "Ways to reach the contact." + if (update) " null or [] removes them all." else ""
        return """
            "channels": {
              "type": ["array", "null"],
              "description": "$description",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ${if (update) """["kind", "value", "label"]""" else """["kind", "value"]"""},
                "properties": {
                  "kind": {"enum": $KINDS},
                  "value": {"type": "string"$text},
                  "label": {"type": ["string", "null"]$text, "description": "For example work or mobile."}
                }
              }
            }
            """.trimIndent()
    }

    private fun role(update: Boolean) =
        """"role": {"type": ["string", "null"]${notBlank(update)}, "description": "For example Recruiter."}"""

    private fun notes(update: Boolean) =
        """"relationshipNotes": {"type": ["string", "null"]${notBlank(
            update,
        )}, "description": "Notes on the relationship, Markdown."}"""

    private fun notBlank(update: Boolean) = if (update) CompanyToolInput.NOT_BLANK else ""

    private const val NAME = """"name": {"type": "string", "description": "The contact's name."}"""

    /** The company link, a property of `create_contact` and, beside `contact`, of `update_contact`. */
    const val COMPANY_PROPERTY =
        """"companyId": {"type": ["string", "null"], "format": "uuid", "description": "The company the contact works for."}"""

    /** The JSON Schema properties of `create_contact`. */
    val PROPERTIES = listOf(NAME, role(false), COMPANY_PROPERTY, channels(false), notes(false)).joinToString(",\n")

    /** The `contact` object of `update_contact`: every field required, `null` (or `[]`) clears it. */
    val UPDATE_OBJECT =
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["name", "role", "channels", "relationshipNotes"],
          "description": "The `content` of get_contact's `contact` (not the wrapper around it), changed as meant.",
          "properties": {${listOf(NAME, role(true), channels(true), notes(true)).joinToString(",\n")}}
        }
        """.trimIndent()
}
