// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.github.scriptibus.jofi.shared.domain.EntityRef
import java.time.Instant

/**
 * The user's settings for the applications' automatic suggestions, one set for the whole app (ADR-0050): after how
 * many weeks without news an application in `APPLIED` or `INTERVIEWING` is suggested as `GHOSTED` (spec §6.2, #85),
 * and after how many days without a response after applying a "follow up" task is suggested (spec §6.1, #95). Until
 * the user changes them, the [DEFAULT] applies ([version] 0, never stored, no [updatedAt]); the first change stores
 * version 1. [version] counts changes (ADR-0041).
 */
data class ApplicationSettings(
    val values: Values,
    val version: Long,
    val updatedAt: Instant?,
) {
    init {
        require(version >= 0) { "A settings version must not be negative" }
        require((version == 0L) == (updatedAt == null)) { "Settings have a change time exactly once changed" }
    }

    /** The settings with new [values], changed [at]; the same settings if they are unchanged. */
    fun edit(
        values: Values,
        at: Instant,
    ): ApplicationSettings = if (values == this.values) this else ApplicationSettings(values, version + 1, at)

    /** The settings' values, each within its bounds. */
    data class Values(
        val ghostedAfterWeeks: Int,
        val followUpAfterDays: Int,
    ) {
        init {
            require(ghostedAfterWeeks in GHOSTED_WEEKS) { "Ghosted is suggested after $GHOSTED_WEEKS weeks" }
            require(followUpAfterDays in FOLLOW_UP_DAYS) { "A follow-up is suggested after $FOLLOW_UP_DAYS days" }
        }
    }

    companion object {
        // The bounds come first: the defaults below are checked against them while the companion initializes.

        /** From one week to a year: a longer silence is no longer worth a suggestion. */
        val GHOSTED_WEEKS = 1..52

        /** From one day to about three months. */
        val FOLLOW_UP_DAYS = 1..90

        /** The spec's defaults: Ghosted after 14 weeks (§6.2), a follow-up 14 days after applying (§6.1). */
        val DEFAULT_VALUES = Values(ghostedAfterWeeks = 14, followUpAfterDays = 14)

        /** The settings before the user changed anything. */
        val DEFAULT = ApplicationSettings(DEFAULT_VALUES, version = 0, updatedAt = null)

        /** The changelog's entity type of the settings; there is only one set, so its id is fixed. */
        const val ENTITY_TYPE = "application_settings"

        /** How changelog entries refer to the settings. */
        val ENTITY_REF: EntityRef = EntityRef(ENTITY_TYPE, "applications")
    }
}

/** New settings as a client sent them. [validate] reports every value out of its bounds. */
data class ApplicationSettingsInput(
    val ghostedAfterWeeks: Int,
    val followUpAfterDays: Int,
) {
    fun validate(): ApplicationValidation<ApplicationSettings.Values> {
        val violations =
            listOfNotNull(
                violation(ApplicationField.GHOSTED_AFTER_WEEKS, ghostedAfterWeeks, ApplicationSettings.GHOSTED_WEEKS),
                violation(ApplicationField.FOLLOW_UP_AFTER_DAYS, followUpAfterDays, ApplicationSettings.FOLLOW_UP_DAYS),
            )
        return if (violations.isEmpty()) {
            ApplicationValidation.Valid(ApplicationSettings.Values(ghostedAfterWeeks, followUpAfterDays))
        } else {
            ApplicationValidation.Invalid(violations)
        }
    }

    private fun violation(
        field: ApplicationField,
        value: Int,
        bounds: IntRange,
    ): ApplicationViolation? =
        ApplicationViolation(field, ApplicationProblem.OUT_OF_RANGE).takeUnless { value in bounds }
}
