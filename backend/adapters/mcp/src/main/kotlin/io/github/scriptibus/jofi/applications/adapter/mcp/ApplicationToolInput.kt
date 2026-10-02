// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.EmploymentType
import io.github.scriptibus.jofi.applications.domain.EstimateConfidence
import io.github.scriptibus.jofi.applications.domain.FormOfAddress
import io.github.scriptibus.jofi.applications.domain.HowApplied
import io.github.scriptibus.jofi.applications.domain.LanguageAndToneInput
import io.github.scriptibus.jofi.applications.domain.OfferInput
import io.github.scriptibus.jofi.applications.domain.PayBandInput
import io.github.scriptibus.jofi.applications.domain.PayInput
import io.github.scriptibus.jofi.applications.domain.PayPeriod
import io.github.scriptibus.jofi.applications.domain.PaySourceKind
import io.github.scriptibus.jofi.applications.domain.Seniority
import io.github.scriptibus.jofi.applications.domain.Tone
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments

/**
 * The application fields `create_application` and `update_application` share: arguments in, the domain's input
 * out. The names are those of `get_application`'s answer (its texts are in `posting` and `notes` there), so an
 * answer can be sent back with `null` for what is not set.
 */
internal object ApplicationToolInput {
    /** A missing title is an empty one, which the domain's validation reports as `required`. */
    fun of(arguments: ToolArguments) =
        ApplicationInput(
            title = arguments.text("title").orEmpty(),
            company = CompanyRef(arguments.uuid("companyId") ?: throw InvalidToolArgument("companyId")),
            location = arguments.text("location"),
            remoteShare = arguments.int("remoteSharePercent"),
            employmentType = arguments.enum("employmentType", EmploymentType::class.java),
            seniority = arguments.enum("seniority", Seniority::class.java),
            deadline = arguments.date("deadline"),
            howApplied = arguments.enum("howApplied", HowApplied::class.java),
            portalNotes = arguments.text("portalNotes"),
            payBand = arguments.obj("payBand")?.let(::payBandOf),
            languageAndTone = arguments.obj("languageAndTone")?.let(::languageAndToneOf),
            offer = arguments.obj("offer")?.let(::offerOf),
        )

    private fun payBandOf(band: ToolArguments) =
        PayBandInput(
            min = band.decimal("min"),
            max = band.decimal("max"),
            currency = band.text("currency").orEmpty(),
            period = band.enum("period", PayPeriod::class.java) ?: throw InvalidToolArgument("payBand"),
            source = band.enum("source", PaySourceKind::class.java) ?: throw InvalidToolArgument("payBand"),
            estimateBasis = band.text("estimateBasis"),
            estimateConfidence = band.enum("estimateConfidence", EstimateConfidence::class.java),
        )

    private fun languageAndToneOf(values: ToolArguments) =
        LanguageAndToneInput(
            postingLanguage = values.text("postingLanguage"),
            applicationLanguage = values.text("applicationLanguage"),
            formOfAddress = values.enum("formOfAddress", FormOfAddress::class.java),
            tone = values.enum("tone", Tone::class.java),
        )

    private fun offerOf(offer: ToolArguments) =
        OfferInput(
            salary = offer.obj("salary")?.let(::salaryOf),
            bonus = offer.text("bonus"),
            benefits = offer.text("benefits"),
            remoteShare = offer.int("remoteSharePercent"),
            vacationDays = offer.int("vacationDays"),
            noticePeriod = offer.text("noticePeriod"),
            startDate = offer.date("startDate"),
            answerBy = offer.date("answerBy"),
        )

    private fun salaryOf(salary: ToolArguments) =
        PayInput(
            amount = salary.decimal("amount") ?: throw InvalidToolArgument("offer"),
            currency = salary.text("currency").orEmpty(),
            period = salary.enum("period", PayPeriod::class.java) ?: throw InvalidToolArgument("offer"),
        )

    // Bounds against oversized payloads, twice what the domain accepts: the domain reports its own limits.
    private const val MAX_TITLE = 600
    private const val MAX_LOCATION = 400
    private const val MAX_NOTES = 100_000
    private const val MAX_BASIS = 4_000
    private const val MAX_OFFER_TEXT = 10_000
    private const val MAX_NOTICE = 400
    private const val MAX_TAG = 40
    private const val MAX_DATE = 10

    private inline fun <reified E : Enum<E>> names(): String =
        enumValues<E>().joinToString(", ", "[", ", null]") { "\"${it.name}\"" }

    private inline fun <reified E : Enum<E>> required(): String =
        enumValues<E>().joinToString(", ", "[", "]") { "\"${it.name}\"" }

    private val DATE =
        """{"type": ["string", "null"], "maxLength": $MAX_DATE, "description": "A date such as 2026-10-05."}"""

    private val PAY_BAND =
        """
        {
          "type": ["object", "null"],
          "additionalProperties": false,
          "required": ["currency", "period", "source"],
          "description": "Gross pay named by the posting, a recruiter or an estimate; give min, max or both.",
          "properties": {
            "min": {"type": ["number", "null"]},
            "max": {"type": ["number", "null"]},
            "currency": {"type": "string", "maxLength": 16, "description": "ISO 4217, such as EUR."},
            "period": {"enum": ${required<PayPeriod>()}},
            "source": {"enum": ${required<PaySourceKind>()}},
            "estimateBasis": {"type": ["string", "null"], "maxLength": $MAX_BASIS,
              "description": "What an ESTIMATED band rests on; required then."},
            "estimateConfidence": {"enum": ${names<EstimateConfidence>()}, "description": "Required for ESTIMATED."}
          }
        }
        """.trimIndent()

    private val LANGUAGE_AND_TONE =
        """
        {
          "type": ["object", "null"],
          "additionalProperties": false,
          "properties": {
            "postingLanguage": {"type": ["string", "null"], "maxLength": $MAX_TAG, "description": "BCP 47, such as de."},
            "applicationLanguage": {"type": ["string", "null"], "maxLength": $MAX_TAG},
            "formOfAddress": {"enum": ${names<FormOfAddress>()}},
            "tone": {"enum": ${names<Tone>()}}
          }
        }
        """.trimIndent()

    private val OFFER =
        """
        {
          "type": ["object", "null"],
          "additionalProperties": false,
          "description": "What the company offered; at least one detail, or null for no offer.",
          "properties": {
            "salary": {
              "type": ["object", "null"],
              "additionalProperties": false,
              "required": ["amount", "currency", "period"],
              "properties": {
                "amount": {"type": "number"},
                "currency": {"type": "string", "maxLength": 16},
                "period": {"enum": ${required<PayPeriod>()}}
              }
            },
            "bonus": {"type": ["string", "null"], "maxLength": $MAX_OFFER_TEXT},
            "benefits": {"type": ["string", "null"], "maxLength": $MAX_OFFER_TEXT},
            "remoteSharePercent": {"type": ["integer", "null"]},
            "vacationDays": {"type": ["integer", "null"]},
            "noticePeriod": {"type": ["string", "null"], "maxLength": $MAX_NOTICE},
            "startDate": $DATE,
            "answerBy": $DATE
          }
        }
        """.trimIndent()

    /** The JSON Schema properties of the fields above; optional ones accept `null` as "not set". */
    val PROPERTIES =
        """
        "companyId": {"type": "string", "format": "uuid", "description": "The company the job is at."},
        "title": {"type": "string", "maxLength": $MAX_TITLE, "description": "The job title."},
        "location": {"type": ["string", "null"], "maxLength": $MAX_LOCATION},
        "remoteSharePercent": {"type": ["integer", "null"], "description": "0 on-site only to 100 fully remote."},
        "employmentType": {"enum": ${names<EmploymentType>()}},
        "seniority": {"enum": ${names<Seniority>()}},
        "deadline": $DATE,
        "howApplied": {"enum": ${names<HowApplied>()}},
        "portalNotes": {"type": ["string", "null"], "maxLength": $MAX_NOTES, "description": "Portal notes, Markdown."},
        "payBand": $PAY_BAND,
        "languageAndTone": $LANGUAGE_AND_TONE,
        "offer": $OFFER
        """.trimIndent()
}
