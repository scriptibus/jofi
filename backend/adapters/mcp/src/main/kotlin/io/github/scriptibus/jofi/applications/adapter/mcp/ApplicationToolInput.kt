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
import io.github.scriptibus.jofi.shared.adapter.mcp.ArgumentProblem
import io.github.scriptibus.jofi.shared.adapter.mcp.InvalidToolArgument
import io.github.scriptibus.jofi.shared.adapter.mcp.ToolArguments

/**
 * The application fields `create_application` and `update_application` share: arguments in, the domain's input
 * out. They have the shape of `get_application`'s answer (the `content` of its untrusted objects `posting`, `notes`
 * and `languageAndTone` under the same keys), so an answer goes back without reshaping. In `update_application` every
 * property is required, `null` for "not set" (a replace-style update: only an explicit `null` clears); in
 * `create_application` the optional ones may be left out.
 */
internal object ApplicationToolInput {
    /** A missing title is an empty one, which the domain's validation reports as `required`. */
    fun of(arguments: ToolArguments): ApplicationInput {
        val posting = arguments.obj("posting") ?: throw InvalidToolArgument("posting")
        val notes = arguments.obj("notes")
        return ApplicationInput(
            title = posting.text("title").orEmpty(),
            company = CompanyRef(arguments.uuid("companyId") ?: throw InvalidToolArgument("companyId")),
            location = posting.text("location"),
            remoteShare = arguments.int("remoteSharePercent"),
            employmentType = arguments.enum("employmentType", EmploymentType::class.java),
            seniority = arguments.enum("seniority", Seniority::class.java),
            deadline = arguments.date("deadline"),
            howApplied = arguments.enum("howApplied", HowApplied::class.java),
            portalNotes = notes?.text("portalNotes"),
            payBand = arguments.obj("payBand")?.let { payBandOf(it, notes?.text("payEstimateBasis")) },
            languageAndTone = arguments.obj("languageAndTone")?.let(::languageAndToneOf),
            offer = offerOf(arguments.obj("offer"), notes?.obj("offer")),
        )
    }

    /**
     * What the arguments say against each other, which the domain would resolve by dropping something silently: a
     * pay estimate basis without an `ESTIMATED` band, and (an update sends both halves) an `offer` and a `notes.offer`
     * of which only one is `null`, which would clear the details and keep the texts or the reverse.
     */
    fun conflicts(
        arguments: ToolArguments,
        update: Boolean,
    ): List<ArgumentProblem> {
        val notes = arguments.obj("notes")
        val estimated = arguments.obj("payBand")?.enum("source", PaySourceKind::class.java) == PaySourceKind.ESTIMATED
        val basis = if (notes?.text("payEstimateBasis") != null && !estimated) listOf(NOT_APPLICABLE) else emptyList()
        val halves = listOf(arguments.obj("offer") == null, notes?.obj("offer") == null)
        val offer = if (update && halves.distinct().size > 1) listOf(OFFER_HALF, NOTES_OFFER_HALF) else emptyList()
        return basis + offer
    }

    private val NOT_APPLICABLE = ArgumentProblem("notes.payEstimateBasis", "not-applicable")
    private val OFFER_HALF = ArgumentProblem("offer", "inconsistent")
    private val NOTES_OFFER_HALF = ArgumentProblem("notes.offer", "inconsistent")

    private fun payBandOf(
        band: ToolArguments,
        basis: String?,
    ) = PayBandInput(
        min = band.decimal("min"),
        max = band.decimal("max"),
        currency = band.text("currency").orEmpty(),
        period = band.enum("period", PayPeriod::class.java) ?: throw InvalidToolArgument("payBand"),
        source = band.enum("source", PaySourceKind::class.java) ?: throw InvalidToolArgument("payBand"),
        estimateBasis = basis,
        estimateConfidence = band.enum("estimateConfidence", EstimateConfidence::class.java),
    )

    private fun languageAndToneOf(values: ToolArguments) =
        LanguageAndToneInput(
            postingLanguage = values.text("postingLanguage"),
            applicationLanguage = values.text("applicationLanguage"),
            formOfAddress = values.enum("formOfAddress", FormOfAddress::class.java),
            tone = values.enum("tone", Tone::class.java),
        )

    /** The offer is split in the answer: typed details in `offer`, its texts in `notes.offer`. Neither: no offer. */
    private fun offerOf(
        offer: ToolArguments?,
        texts: ToolArguments?,
    ): OfferInput? =
        if (offer == null && texts == null) {
            null
        } else {
            OfferInput(
                salary = offer?.obj("salary")?.let(::salaryOf),
                bonus = texts?.text("bonus"),
                benefits = texts?.text("benefits"),
                remoteShare = offer?.int("remoteSharePercent"),
                vacationDays = offer?.int("vacationDays"),
                noticePeriod = texts?.text("noticePeriod"),
                startDate = offer?.date("startDate"),
                answerBy = offer?.date("answerBy"),
            )
        }

    private fun salaryOf(salary: ToolArguments) =
        PayInput(
            amount = salary.decimal("amount") ?: throw InvalidToolArgument("offer"),
            currency = salary.text("currency").orEmpty(),
            period = salary.enum("period", PayPeriod::class.java) ?: throw InvalidToolArgument("offer"),
        )
}
