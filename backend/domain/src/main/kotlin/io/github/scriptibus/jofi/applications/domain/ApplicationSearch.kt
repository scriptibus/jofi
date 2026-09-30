// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import java.time.Instant

/**
 * A page of the application list (spec §6.3, #83). Every filter that is set must match (AND); a set of values
 * matches any of them (OR). [text] matches titles fuzzily (pg_trgm). [company] and [contact] keep the
 * applications of one company or with one linked contact; [statuses], [unread] and [sourceKinds] (any source
 * of that kind) filter by their value. [languages] match the effective application language
 * ([LanguageAndTone.effectiveApplicationLanguage]) by RFC 4647 basic filtering: `de` matches `de` and `de-CH`,
 * ignoring case. [created] and [updated] are half-open time ranges; [wantScore] and [fitScore] never match an
 * application without that score. Without an [order], the best title match comes first when [text] is set,
 * otherwise the most recently updated. Every order ends with the id, so offset paging is stable while the
 * data does not change. [page] counts from 0. Build one from untrusted input with [ApplicationSearchInput].
 */
data class ApplicationSearch(
    val text: String? = null,
    val company: CompanyRef? = null,
    val contact: ContactRef? = null,
    val statuses: Set<ApplicationStatus> = emptySet(),
    val unread: Boolean? = null,
    val languages: Set<LanguageTag> = emptySet(),
    val sourceKinds: Set<SourceKind> = emptySet(),
    val created: TimeRange? = null,
    val updated: TimeRange? = null,
    val wantScore: ScoreRange? = null,
    val fitScore: ScoreRange? = null,
    val order: ApplicationOrder? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(text == null || text.isNotBlank()) { "A search text, when given, must not be blank" }
        require(text == null || text.length <= MAX_TEXT_LENGTH) { "A search text is at most $MAX_TEXT_LENGTH long" }
        require(languages.size <= MAX_LANGUAGES) { "A search names at most $MAX_LANGUAGES languages" }
        require(page >= 0) { "A page number must not be negative" }
        require(size in 1..MAX_SIZE) { "A page holds 1 to $MAX_SIZE applications" }
    }

    /** Leaves out [text], which may quote a title. */
    override fun toString(): String =
        "ApplicationSearch(company=$company, contact=$contact, statuses=$statuses, unread=$unread, " +
            "languages=$languages, sourceKinds=$sourceKinds, created=$created, updated=$updated, " +
            "wantScore=$wantScore, fitScore=$fitScore, order=$order, page=$page, size=$size)"

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 200

        /** As long as a title can be: a longer text cannot match one. */
        const val MAX_TEXT_LENGTH = ApplicationDetails.MAX_TITLE_LENGTH
        const val MAX_LANGUAGES = 20
    }
}

/** From [from] (inclusive) to [to] (exclusive); at least one end is set. */
data class TimeRange(
    val from: Instant?,
    val to: Instant?,
) {
    init {
        require(from != null || to != null) { "A time range has at least one end" }
        require(from == null || to == null || from.isBefore(to)) { "A time range ends after it starts" }
    }
}

/** From [min] to [max], both inclusive; at least one end is set. */
data class ScoreRange(
    val min: Score?,
    val max: Score?,
) {
    init {
        require(min != null || max != null) { "A score range has at least one end" }
        require(
            min == null || max == null || min.tenths <= max.tenths,
        ) { "A score range's minimum is below its maximum" }
    }
}

/** What the list is sorted by, and the direction it takes when none is given. */
enum class ApplicationSortKey(
    val defaultDirection: SortDirection,
) {
    UPDATED(SortDirection.DESCENDING),
    CREATED(SortDirection.DESCENDING),
    TITLE(SortDirection.ASCENDING),

    /** By the company's name. */
    COMPANY(SortDirection.ASCENDING),

    /** In pipeline order ([ApplicationStatus]'s declaration order), not alphabetically. */
    STATUS(SortDirection.ASCENDING),

    /** Applications without a deadline come last in both directions. */
    DEADLINE(SortDirection.ASCENDING),
}

enum class SortDirection { ASCENDING, DESCENDING }

data class ApplicationOrder(
    val key: ApplicationSortKey,
    val direction: SortDirection = key.defaultDirection,
)
