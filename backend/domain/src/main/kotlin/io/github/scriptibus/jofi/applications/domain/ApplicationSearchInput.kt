// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.text.normalizedText
import java.math.BigDecimal
import java.time.Instant

/** A search parameter with a problem, named so that clients can show it next to their control. */
enum class SearchField { TEXT, LANGUAGES, CREATED_TO, UPDATED_TO, WANT_MIN, WANT_MAX, FIT_MIN, FIT_MAX, PAGE, SIZE }

data class SearchViolation(
    val field: SearchField,
    val problem: ApplicationProblem,
)

/** Result of validating untrusted search parameters: the search, or every problem found. */
sealed interface SearchValidation {
    data class Valid(
        val search: ApplicationSearch,
    ) : SearchValidation

    data class Invalid(
        val violations: List<SearchViolation>,
    ) : SearchValidation {
        init {
            require(violations.isNotEmpty()) { "An invalid search names at least one violation" }
        }
    }
}

/**
 * Search parameters as a client sent them (REST query, MCP tool). [validate] normalizes [text] to NFC and trims
 * it (blank searches everything), brings [languages] into canonical case, reads scores as 0 to 5 with one
 * decimal, and reports what is wrong: a text too long or unstorable, an invalid or 21st language, a range whose
 * end is not after its start (named by its end), a score out of range or too precise, a page or size out of
 * range. A [direction] without [sort] applies to the default key, [ApplicationSortKey.UPDATED].
 */
data class ApplicationSearchInput(
    val text: String? = null,
    val company: CompanyRef? = null,
    val contact: ContactRef? = null,
    val statuses: Set<ApplicationStatus> = emptySet(),
    val unread: Boolean? = null,
    val languages: List<String> = emptyList(),
    val sourceKinds: Set<SourceKind> = emptySet(),
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val updatedFrom: Instant? = null,
    val updatedTo: Instant? = null,
    val wantMin: BigDecimal? = null,
    val wantMax: BigDecimal? = null,
    val fitMin: BigDecimal? = null,
    val fitMax: BigDecimal? = null,
    val sort: ApplicationSortKey? = null,
    val direction: SortDirection? = null,
    val page: Int = 0,
    val size: Int = ApplicationSearch.DEFAULT_SIZE,
) {
    fun validate(): SearchValidation {
        val checks = SearchChecks()
        val search =
            ApplicationSearch(
                text = checks.text(text),
                company = company,
                contact = contact,
                statuses = statuses,
                unread = unread,
                languages = checks.languages(languages),
                sourceKinds = sourceKinds,
                created = checks.timeRange(SearchField.CREATED_TO, createdFrom, createdTo),
                updated = checks.timeRange(SearchField.UPDATED_TO, updatedFrom, updatedTo),
                wantScore = checks.scoreRange(SearchField.WANT_MIN to wantMin, SearchField.WANT_MAX to wantMax),
                fitScore = checks.scoreRange(SearchField.FIT_MIN to fitMin, SearchField.FIT_MAX to fitMax),
                order = order(),
                page = checks.page(page),
                size = checks.size(size),
            )
        return if (checks.violations.isEmpty()) {
            SearchValidation.Valid(
                search,
            )
        } else {
            SearchValidation.Invalid(checks.violations)
        }
    }

    private fun order(): ApplicationOrder? =
        when {
            sort != null -> ApplicationOrder(sort, direction ?: sort.defaultDirection)
            direction != null -> ApplicationOrder(ApplicationSortKey.UPDATED, direction)
            else -> null
        }

    /** Leaves out [text], which may quote a title. */
    override fun toString(): String = "ApplicationSearchInput(page=$page, size=$size)"
}

/** Collects violations; every check answers a value the search accepts (a neutral one after a violation). */
private class SearchChecks {
    val violations = mutableListOf<SearchViolation>()

    private fun report(
        field: SearchField,
        problem: ApplicationProblem,
    ) {
        violations += SearchViolation(field, problem)
    }

    fun text(raw: String?): String? {
        val text = raw?.normalizedText()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val problem = ApplicationRules.textProblemOf(text, ApplicationSearch.MAX_TEXT_LENGTH)
        if (problem != null) report(SearchField.TEXT, problem)
        return text.takeIf { problem == null }
    }

    fun languages(raw: List<String>): Set<LanguageTag> {
        val tags = raw.map(String::trim).filter(String::isNotEmpty)
        if (tags.any { !LanguageTag.isValid(it) }) report(SearchField.LANGUAGES, ApplicationProblem.INVALID_LANGUAGE)
        val valid = tags.filter(LanguageTag::isValid).mapTo(linkedSetOf()) { LanguageTag(LanguageTag.canonical(it)) }
        if (valid.size <= ApplicationSearch.MAX_LANGUAGES) return valid
        report(SearchField.LANGUAGES, ApplicationProblem.TOO_MANY)
        return emptySet()
    }

    fun timeRange(
        end: SearchField,
        from: Instant?,
        to: Instant?,
    ): TimeRange? =
        when {
            from == null && to == null -> null

            from != null && to != null &&
                !from.isBefore(
                    to,
                )
            -> null.also { report(end, ApplicationProblem.OUT_OF_RANGE) }

            else -> TimeRange(from, to)
        }

    fun scoreRange(
        min: Pair<SearchField, BigDecimal?>,
        max: Pair<SearchField, BigDecimal?>,
    ): ScoreRange? {
        val low = score(min.first, min.second)
        val high = score(max.first, max.second)
        return when {
            low == null && high == null -> {
                null
            }

            low != null && high != null && low.tenths > high.tenths -> {
                null.also {
                    report(max.first, ApplicationProblem.OUT_OF_RANGE)
                }
            }

            else -> {
                ScoreRange(low, high)
            }
        }
    }

    private fun score(
        field: SearchField,
        value: BigDecimal?,
    ): Score? {
        val tenths = value?.movePointRight(1) ?: return null
        val problem =
            when {
                tenths.signum() < 0 || tenths >
                    BigDecimal.valueOf(
                        Score.MAX_TENTHS.toLong(),
                    )
                -> ApplicationProblem.OUT_OF_RANGE

                tenths.stripTrailingZeros().scale() > 0 -> ApplicationProblem.TOO_PRECISE

                else -> null
            }
        if (problem != null) report(field, problem)
        return if (problem == null) Score(tenths.intValueExact()) else null
    }

    fun page(page: Int): Int =
        page.takeIf { it >= 0 } ?: 0.also { report(SearchField.PAGE, ApplicationProblem.OUT_OF_RANGE) }

    fun size(size: Int): Int =
        size.takeIf { it in 1..ApplicationSearch.MAX_SIZE }
            ?: ApplicationSearch.DEFAULT_SIZE.also { report(SearchField.SIZE, ApplicationProblem.OUT_OF_RANGE) }
}
