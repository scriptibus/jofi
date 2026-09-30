// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

import java.net.URI

/**
 * What the user maintains about a company (spec §5). Build it from untrusted input with
 * [CompanyInput.validate], which reports every problem as a value; the constructor only guards
 * invariants and throws on a programming error.
 */
data class CompanyDetails(
    val name: String,
    val website: WebAddress? = null,
    val industry: String? = null,
    val size: CompanySize? = null,
    /** Where the company works (cities, regions or "remote"), without duplicates; the first is the main one. */
    val locations: List<String> = emptyList(),
    /** The careers page or ATS board where the company posts jobs. */
    val careersPage: WebAddress? = null,
    /** The user's research notes, Markdown. */
    val researchNotes: String? = null,
) {
    init {
        require(CompanyRules.violations(name, industry, locations, researchNotes).isEmpty()) {
            "Company details break an invariant"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_INDUSTRY_LENGTH = 200
        const val MAX_LOCATION_LENGTH = 200
        const val MAX_LOCATIONS = 50
        const val MAX_NOTES_LENGTH = 50_000
    }
}

/** Head-count bands (employees), coarse enough to fill in from a careers page or a register entry. */
enum class CompanySize {
    /** 1 to 9. */
    MICRO,

    /** 10 to 49. */
    SMALL,

    /** 50 to 249. */
    MEDIUM,

    /** 250 to 4,999. */
    LARGE,

    /** 5,000 or more. */
    ENTERPRISE,
}

/**
 * An absolute http(s) URL with a host and without user info (credentials never belong in a stored
 * link), at most [MAX_LENGTH] characters.
 */
@JvmInline
value class WebAddress(
    val value: URI,
) {
    init {
        require(isValid(value)) { "A web address must be an absolute http(s) URL with a host and no user info" }
    }

    override fun toString(): String = value.toString()

    companion object {
        const val MAX_LENGTH = 2_048
        private val SCHEMES = setOf("http", "https")

        /** The address [raw] names, or `null` if it is not a valid web address. */
        fun parse(raw: String): WebAddress? = runCatching { URI(raw) }.getOrNull()?.takeIf(::isValid)?.let(::WebAddress)

        private fun isValid(uri: URI): Boolean =
            uri.toString().length <= MAX_LENGTH &&
                uri.isAbsolute &&
                uri.scheme.lowercase() in SCHEMES &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null
    }
}

/** A problem with one field of a [CompanyInput], named so that clients can show it next to the field. */
data class CompanyViolation(
    val field: CompanyField,
    val problem: ViolationKind,
)

enum class CompanyField { NAME, WEBSITE, INDUSTRY, LOCATIONS, CAREERS_PAGE, RESEARCH_NOTES, PREFERENCE_REASON }

enum class ViolationKind {
    /** The field is required but empty. */
    REQUIRED,

    /** The text, or one entry of a list, is longer than allowed. */
    TOO_LONG,

    /** The list has more entries than allowed. */
    TOO_MANY,

    /** Not an absolute http(s) URL with a host and without user info. */
    INVALID_URL,
}

/** The rules of [CompanyDetails], shared by its invariants and [CompanyInput.validate]. */
internal object CompanyRules {
    fun violations(
        name: String,
        industry: String?,
        locations: List<String>,
        researchNotes: String?,
    ): List<CompanyViolation> =
        listOfNotNull(
            text(CompanyField.NAME, name, CompanyDetails.MAX_NAME_LENGTH),
            industry?.let { text(CompanyField.INDUSTRY, it, CompanyDetails.MAX_INDUSTRY_LENGTH) },
            researchNotes?.let { text(CompanyField.RESEARCH_NOTES, it, CompanyDetails.MAX_NOTES_LENGTH) },
            locations(locations),
        )

    private fun text(
        field: CompanyField,
        value: String,
        maxLength: Int,
    ): CompanyViolation? =
        when {
            value.isBlank() || value != value.trim() -> CompanyViolation(field, ViolationKind.REQUIRED)
            value.length > maxLength -> CompanyViolation(field, ViolationKind.TOO_LONG)
            else -> null
        }

    private fun locations(locations: List<String>): CompanyViolation? =
        when {
            locations.size > CompanyDetails.MAX_LOCATIONS -> {
                CompanyViolation(CompanyField.LOCATIONS, ViolationKind.TOO_MANY)
            }

            locations.any { it.length > CompanyDetails.MAX_LOCATION_LENGTH } -> {
                CompanyViolation(CompanyField.LOCATIONS, ViolationKind.TOO_LONG)
            }

            locations.any { it.isBlank() || it != it.trim() } ||
                locations.distinctBy { it.lowercase() }.size != locations.size -> {
                CompanyViolation(CompanyField.LOCATIONS, ViolationKind.REQUIRED)
            }

            else -> {
                null
            }
        }
}
