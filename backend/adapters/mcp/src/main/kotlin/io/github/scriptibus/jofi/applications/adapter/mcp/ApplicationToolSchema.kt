// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.Tone

/**
 * The JSON Schemas of `create_application` and `update_application`: the shape of [ApplicationToolInput]. In
 * `update_application` every property is required, nested ones included, and `null` clears (the rule for replace-style
 * updates, docs/mcp-tools.md); in `create_application` the optional ones may be left out.
 */
internal object ApplicationToolSchema {
    // Bounds against oversized payloads, twice what the domain accepts: the domain reports its own limits.
    private const val MAX_TITLE = 600
    private const val MAX_LOCATION = 400
    private const val MAX_NOTES = 100_000
    private const val MAX_BASIS = 4_000
    private const val MAX_OFFER_TEXT = 10_000
    private const val MAX_NOTICE = 400
    private const val MAX_TAG = 40
    private const val MAX_DATE = 10
    private const val MAX_CURRENCY = 16

    private inline fun <reified E : Enum<E>> names(nullable: Boolean = true): String =
        enumValues<E>().joinToString(", ", "[", if (nullable) ", null]" else "]") { "\"${it.name}\"" }

    /** `"required": [...]` for [names] when [all] properties must be sent (an update), else for [always] only. */
    private fun required(
        all: Boolean,
        names: List<String>,
        always: List<String> = emptyList(),
    ): String {
        val keys = if (all) names else always
        return if (keys.isEmpty()) "" else keys.joinToString(", ", "\"required\": [", "],") { "\"$it\"" }
    }

    private val DATE =
        """{"type": ["string", "null"], "maxLength": $MAX_DATE, "description": "A date such as 2026-10-05."}"""

    private fun text(
        max: Int,
        description: String = "",
    ) = """{"type": ["string", "null"], "maxLength": $max, "description": "$description"}"""

    private fun payBand(update: Boolean) =
        """
        {
          "type": ["object", "null"],
          "additionalProperties": false,
          ${required(
            update,
            listOf("min", "max", "currency", "period", "source", "estimateConfidence"),
            listOf("currency", "period", "source"),
        )}
          "description": "Gross pay named by the posting, a recruiter or an estimate; min, max or both.",
          "properties": {
            "min": {"type": ["number", "null"]},
            "max": {"type": ["number", "null"]},
            "currency": {"type": "string", "maxLength": $MAX_CURRENCY, "description": "ISO 4217, such as EUR."},
            "period": {"enum": ${names<PayPeriod>(false)}},
            "source": {"enum": ${names<PaySourceKind>(false)}},
            "estimateConfidence": {"enum": ${names<EstimateConfidence>()}, "description": "Required for ESTIMATED."}
          }
        }
        """.trimIndent()

    private fun languageAndTone(update: Boolean) =
        """
        {
          "type": ${objectType(update)},
          "additionalProperties": false,
          ${required(update, listOf("postingLanguage", "applicationLanguage", "formOfAddress", "tone"))}
          "properties": {
            "postingLanguage": ${text(MAX_TAG, "BCP 47, e.g. de.")},
            "applicationLanguage": ${text(MAX_TAG)},
            "formOfAddress": {"enum": ${names<FormOfAddress>()}},
            "tone": {"enum": ${names<Tone>()}}
          }
        }
        """.trimIndent()

    private fun offer(update: Boolean) =
        """
        {
          "type": ["object", "null"],
          "additionalProperties": false,
          ${required(update, listOf("salary", "remoteSharePercent", "vacationDays", "startDate", "answerBy"))}
          "description": "The typed details of what the company offered (its texts are in notes.offer).",
          "properties": {
            "salary": {
              "type": ["object", "null"],
              "additionalProperties": false,
              "required": ["amount", "currency", "period"],
              "properties": {
                "amount": {"type": "number"},
                "currency": {"type": "string", "maxLength": $MAX_CURRENCY},
                "period": {"enum": ${names<PayPeriod>(false)}}
              }
            },
            "remoteSharePercent": {"type": ["integer", "null"]},
            "vacationDays": {"type": ["integer", "null"]},
            "startDate": $DATE,
            "answerBy": $DATE
          }
        }
        """.trimIndent()

    private fun posting(update: Boolean) =
        """
        {
          "type": "object",
          "additionalProperties": false,
          ${required(update, listOf("title", "location"), listOf("title"))}
          "properties": {
            "title": {"type": "string", "maxLength": $MAX_TITLE, "description": "The job title."},
            "location": ${text(MAX_LOCATION)}
          }
        }
        """.trimIndent()

    private fun notes(update: Boolean) =
        """
        {
          "type": ${objectType(update)},
          "additionalProperties": false,
          ${required(update, listOf("portalNotes", "payEstimateBasis", "offer"))}
          "properties": {
            "portalNotes": ${text(MAX_NOTES, "Portal notes, Markdown.")},
            "payEstimateBasis": ${text(MAX_BASIS, "What an ESTIMATED pay band rests on.")},
            "offer": {
              "type": ["object", "null"],
              "additionalProperties": false,
              ${required(update, listOf("bonus", "benefits", "noticePeriod"))}
              "properties": {
                "bonus": ${text(MAX_OFFER_TEXT)},
                "benefits": ${text(MAX_OFFER_TEXT)},
                "noticePeriod": ${text(MAX_NOTICE)}
              }
            }
          }
        }
        """.trimIndent()

    /** The JSON Schema of `create_application` ([update] false) or `update_application` (true). */
    fun schema(update: Boolean): String {
        val fields =
            listOf(
                "companyId",
                "remoteSharePercent",
                "employmentType",
                "seniority",
                "deadline",
                "howApplied",
                "payBand",
                "offer",
                "languageAndTone",
                "posting",
                "notes",
            )
        val required = if (update) listOf("id", "version") + fields else listOf("companyId", "posting")
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
        ${if (update) IDENTITY else ""}
        "companyId": {"type": "string", "format": "uuid", "description": "The company the job is at."},
        "remoteSharePercent": {"type": ["integer", "null"], "description": "0 on-site only to 100 fully remote."},
        "employmentType": {"enum": ${names<EmploymentType>()}},
        "seniority": {"enum": ${names<Seniority>()}},
        "deadline": $DATE,
        "howApplied": {"enum": ${names<HowApplied>()}},
        "payBand": ${payBand(update)},
        "offer": ${offer(update)},
        "languageAndTone": ${languageAndTone(update)},
        "posting": ${posting(update)},
        "notes": ${notes(update)}
        """.trimIndent()

    /** An object that an update always sends, and a create call may leave out or set to `null`. */
    private fun objectType(update: Boolean) = if (update) "\"object\"" else "[\"object\", \"null\"]"

    private const val IDENTITY =
        """"id": {"type": "string", "format": "uuid"}, "version": {"type": "integer", "minimum": 0},"""
}
