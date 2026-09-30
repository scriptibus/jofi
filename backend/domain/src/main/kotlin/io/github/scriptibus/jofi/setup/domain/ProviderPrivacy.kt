// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.domain

import java.net.URI
import java.time.LocalDate

/**
 * What a provider's official pages say about one privacy question (spec §3.2): is zero data retention
 * offered, is there a promise not to train on API data, can the customer choose where data is processed.
 */
enum class PrivacyClaimStatus {
    /** Yes, for every API customer, without asking. */
    YES,

    /** Only after the provider approves a request (sales, support). */
    ON_REQUEST,

    /** Only on some plans, in some regions or after a setting is changed; the summary says which. */
    CONDITIONAL,

    /** No, or the pages point elsewhere for it. */
    NO,

    /** An OpenAI-compatible endpoint: whatever the configured endpoint's operator offers. */
    DEPENDS_ON_ENDPOINT,

    /** The pages do not say. Never replaced by a guess. */
    UNKNOWN,
}

/** A text the user reads, in both UI languages. */
data class LocalizedText(
    val en: String,
    val de: String,
) {
    init {
        require(en.isNotBlank() && de.isNotBlank()) { "A text needs English and German" }
    }
}

/** A short verbatim [quote] from the official page at [source] that a claim rests on. */
data class PrivacyEvidence(
    val source: URI,
    val quote: String,
) {
    init {
        require(source.scheme == "https" && !source.host.isNullOrBlank()) { "Evidence needs an https source" }
        require(quote.isNotBlank()) { "Evidence quotes its source" }
    }
}

/** One answer with the [evidence] it was read from; a claim without a source is not a claim. */
data class PrivacyClaim(
    val status: PrivacyClaimStatus,
    val summary: LocalizedText,
    val evidence: List<PrivacyEvidence>,
) {
    init {
        require(evidence.isNotEmpty()) { "A privacy claim names at least one source" }
    }
}

/**
 * The privacy terms of one provider kind's API (consumer apps have other terms), as read on
 * [checkedOn]: zero data retention, the promise not to train on API data, and where data is processed.
 */
data class ProviderPrivacyInfo(
    val provider: ProviderKind,
    val checkedOn: LocalDate,
    val zeroDataRetention: PrivacyClaim,
    val noTraining: PrivacyClaim,
    val dataLocation: PrivacyClaim,
) {
    /** Whether the terms were read more than [staleAfterMonths] months before [today] and need a new read. */
    fun isStaleOn(
        today: LocalDate,
        staleAfterMonths: Int,
    ): Boolean = checkedOn.plusMonths(staleAfterMonths.toLong()) < today
}

/** The disclaimer shown with every privacy entry: the user must verify the terms (spec §3.2). */
data class PrivacyDisclaimer(
    val key: String,
    val text: LocalizedText,
) {
    init {
        require(key.isNotBlank()) { "The disclaimer needs a message key" }
    }
}

/**
 * The dated provider privacy info (spec §3.2), maintained by hand from the providers' official pages:
 * exactly one entry per [ProviderKind], none read later than the catalog's [checkedOn]. Entries older
 * than [staleAfterMonths] are flagged as stale so the user knows they may be out of date.
 */
class ProviderPrivacyCatalog(
    val checkedOn: LocalDate,
    val staleAfterMonths: Int,
    val disclaimer: PrivacyDisclaimer,
    entries: List<ProviderPrivacyInfo>,
) {
    val entries: List<ProviderPrivacyInfo> = entries.sortedBy { it.provider }

    init {
        require(staleAfterMonths > 0) { "Entries turn stale after a positive number of months" }
        val kinds = entries.map { it.provider }
        require(kinds.size == kinds.toSet().size) { "A provider kind has at most one privacy entry" }
        require(kinds.toSet() == ProviderKind.entries.toSet()) { "Every provider kind needs a privacy entry" }
        require(entries.all { it.checkedOn <= checkedOn }) { "No entry is newer than the catalog" }
    }

    /** Every entry as of [today], each flagged when it is stale. */
    fun overviewOn(today: LocalDate): ProviderPrivacyOverview =
        ProviderPrivacyOverview(
            checkedOn,
            staleAfterMonths,
            disclaimer,
            entries.map { ProviderPrivacyView(it, it.isStaleOn(today, staleAfterMonths)) },
        )
}

/** A privacy entry and whether it is older than the catalog allows. */
data class ProviderPrivacyView(
    val info: ProviderPrivacyInfo,
    val stale: Boolean,
)

/** The privacy entries of all provider kinds with the disclaimer, as the setup wizard shows them. */
data class ProviderPrivacyOverview(
    val checkedOn: LocalDate,
    val staleAfterMonths: Int,
    val disclaimer: PrivacyDisclaimer,
    val entries: List<ProviderPrivacyView>,
)
