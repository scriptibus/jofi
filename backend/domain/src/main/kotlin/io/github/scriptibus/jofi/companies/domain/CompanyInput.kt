// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.companies.domain

/** Result of validating untrusted input: the domain value, or every problem found. */
sealed interface CompanyValidation<out T> {
    data class Valid<out T>(
        val value: T,
    ) : CompanyValidation<T>

    data class Invalid(
        val violations: List<CompanyViolation>,
    ) : CompanyValidation<Nothing> {
        init {
            require(violations.isNotEmpty()) { "An invalid input names at least one violation" }
        }
    }
}

/**
 * Company details as the user, the AI or an external client entered them. [validate] trims text,
 * treats blank optional fields as absent, drops blank and duplicate locations (ignoring case) and
 * reports what is still wrong.
 */
data class CompanyInput(
    val name: String,
    val website: String? = null,
    val industry: String? = null,
    val size: CompanySize? = null,
    val locations: List<String> = emptyList(),
    val careersPage: String? = null,
    val researchNotes: String? = null,
) {
    fun validate(): CompanyValidation<CompanyDetails> {
        val website = address(CompanyField.WEBSITE, website)
        val careersPage = address(CompanyField.CAREERS_PAGE, careersPage)
        val locations =
            locations
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy(String::lowercase)
        val name = name.trim()
        val industry = industry.trimmedOrNull()
        val researchNotes = researchNotes.trimmedOrNull()
        val violations =
            listOfNotNull(website.violation, careersPage.violation) +
                CompanyRules.violations(name, industry, locations, researchNotes)
        if (violations.isNotEmpty()) return CompanyValidation.Invalid(violations)
        return CompanyValidation.Valid(
            CompanyDetails(name, website.value, industry, size, locations, careersPage.value, researchNotes),
        )
    }

    private class ParsedAddress(
        val value: WebAddress?,
        val violation: CompanyViolation?,
    )

    private fun address(
        field: CompanyField,
        raw: String?,
    ): ParsedAddress {
        val trimmed = raw.trimmedOrNull() ?: return ParsedAddress(null, null)
        val parsed = WebAddress.parse(trimmed)
        return ParsedAddress(parsed, if (parsed == null) CompanyViolation(field, ViolationKind.INVALID_URL) else null)
    }
}

/**
 * The preference the user, the AI or an external client asked for, as [kind] plus an optional
 * [reason]. Only favourites and blacklisted companies keep a reason; [PreferenceKind.NONE] drops it.
 */
data class PreferenceInput(
    val kind: PreferenceKind,
    val reason: String? = null,
) {
    fun validate(): CompanyValidation<CompanyPreference> {
        val reason = reason.trimmedOrNull()?.takeIf { kind != PreferenceKind.NONE }
        if (!CompanyPreference.isValidReason(reason)) {
            val violation = CompanyViolation(CompanyField.PREFERENCE_REASON, ViolationKind.TOO_LONG)
            return CompanyValidation.Invalid(listOf(violation))
        }
        return CompanyValidation.Valid(CompanyPreference.of(kind, reason))
    }
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
