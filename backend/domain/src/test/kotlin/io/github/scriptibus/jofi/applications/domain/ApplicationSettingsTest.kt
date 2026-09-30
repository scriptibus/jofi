// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Instant

class ApplicationSettingsTest {
    private val now = Instant.parse("2026-09-30T10:00:00.123456Z")

    @Test
    fun `the defaults are the spec's, unchanged and never stored`() {
        ApplicationSettings.DEFAULT.values shouldBe
            ApplicationSettings.Values(ghostedAfterWeeks = 14, followUpAfterDays = 14)
        ApplicationSettings.DEFAULT.version shouldBe 0
        ApplicationSettings.DEFAULT.updatedAt shouldBe null
    }

    @Test
    fun `values at exactly their bounds are valid`() {
        listOf(1 to 1, 52 to 90).forEach { (weeks, days) ->
            val valid = ApplicationSettingsInput(weeks, days).validate()
            valid shouldBe ApplicationValidation.Valid(ApplicationSettings.Values(weeks, days))
        }
    }

    @Test
    fun `values out of their bounds are reported together`() {
        ApplicationSettingsInput(0, 91).validate() shouldBe
            ApplicationValidation.Invalid(
                listOf(
                    ApplicationViolation(ApplicationField.GHOSTED_AFTER_WEEKS, ApplicationProblem.OUT_OF_RANGE),
                    ApplicationViolation(ApplicationField.FOLLOW_UP_AFTER_DAYS, ApplicationProblem.OUT_OF_RANGE),
                ),
            )
        val tooHigh = ApplicationSettingsInput(53, 0).validate()
        tooHigh.shouldBeInstanceOf<ApplicationValidation.Invalid>().violations.size shouldBe 2
    }

    @Test
    fun `a change is a new version, an unchanged edit keeps the settings`() {
        val defaults = ApplicationSettings.DEFAULT

        defaults.edit(ApplicationSettings.DEFAULT_VALUES, now) shouldBeSameInstanceAs defaults
        val changed = defaults.edit(ApplicationSettings.Values(26, 7), now)
        changed shouldBe ApplicationSettings(ApplicationSettings.Values(26, 7), 1, now)
        changed.edit(ApplicationSettings.Values(26, 8), now.plusSeconds(1)).version shouldBe 2
    }

    @Test
    fun `invariants and entity`() {
        shouldThrow<IllegalArgumentException> { ApplicationSettings.Values(0, 14) }
        shouldThrow<IllegalArgumentException> { ApplicationSettings.Values(14, 91) }
        shouldThrow<IllegalArgumentException> { ApplicationSettings(ApplicationSettings.DEFAULT_VALUES, 1, null) }
        shouldThrow<IllegalArgumentException> { ApplicationSettings(ApplicationSettings.DEFAULT_VALUES, 0, now) }
        shouldThrow<IllegalArgumentException> { ApplicationSettings(ApplicationSettings.DEFAULT_VALUES, -1, now) }
        ApplicationSettings.ENTITY_REF.type shouldBe "application_settings"
    }
}
