// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.ai

import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.ExtractedPosting
import io.github.scriptibus.jofi.applications.domain.PayBandInput
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Seniority
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.enums.enumEntries

/**
 * The model's answer to the posting extraction, read as untrusted JSON: a plain tree (no types bound from the text),
 * one object, and of its fields only those the schema names, each only with the expected type; anything else counts as
 * absent. Unknown fields (a posting that told the model to add a status) are ignored. `null` if the answer is no JSON
 * object at all.
 */
internal object PostingAnswer {
    private val json = JsonMapper.builder().build()
    private val FENCE = Regex("^```(?:json)?\\s*|\\s*```$")

    fun parse(answer: String): ExtractedPosting? {
        val root =
            try {
                json.readTree(answer.trim().replace(FENCE, ""))
            } catch (_: JacksonException) {
                null
            }
        return root?.takeIf(JsonNode::isObject)?.let(::posting)
    }

    private fun posting(root: JsonNode): ExtractedPosting =
        ExtractedPosting(
            title = root.text("title"),
            company = root.text("company"),
            location = root.text("location"),
            remoteShare = root.get("remoteShare")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue(),
            employmentType = root.enum<EmploymentType>("employmentType"),
            seniority = root.enum<Seniority>("seniority"),
            deadline = root.text("deadline")?.let(::date),
            postingLanguage = root.text("postingLanguage"),
            payBand = root.get("pay")?.takeIf(JsonNode::isObject)?.let(::payBand),
        )

    private fun payBand(pay: JsonNode): PayBandInput? {
        val min = pay.get("min")?.takeIf(JsonNode::isNumber)?.decimalValue()
        val max = pay.get("max")?.takeIf(JsonNode::isNumber)?.decimalValue()
        val currency = pay.text("currency")
        val period = pay.enum<PayPeriod>("period")
        val stated = min != null || max != null
        return if (stated && currency != null && period != null) {
            PayBandInput(min, max, currency, period, PaySourceKind.POSTING)
        } else {
            null
        }
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf(JsonNode::isString)?.stringValue()

    /** The constant named exactly as the field's text; any other text counts as absent. */
    private inline fun <reified T : Enum<T>> JsonNode.enum(field: String): T? =
        text(field)?.let { name -> enumEntries<T>().firstOrNull { it.name == name } }

    private fun date(text: String): LocalDate? =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
}
