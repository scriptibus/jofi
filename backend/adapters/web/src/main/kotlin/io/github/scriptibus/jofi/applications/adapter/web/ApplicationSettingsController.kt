// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.application.GetApplicationSettingsUseCase
import io.github.scriptibus.jofi.applications.application.UpdateApplicationSettingsUseCase
import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import io.github.scriptibus.jofi.shared.domain.Actor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The application settings (spec §6.1, §6.2, ADR-0050): when Ghosted and a follow-up are suggested. Both operations
 * call their use case (#85) as `Actor.User` and map each `ApplicationResult.Failure` with [ApplicationProblems.of].
 */
@RestController
@RequestMapping("/api/applications/settings")
class ApplicationSettingsController(
    private val getSettings: GetApplicationSettingsUseCase,
    private val updateSettings: UpdateApplicationSettingsUseCase,
) {
    /** The settings; the defaults (14 weeks, 14 days, version 0) until the user changes them. */
    @GetMapping
    fun getApplicationSettings(): ApplicationSettingsResponse =
        ApplicationSettingsResponse.from(getSettings.execute().orThrow())

    /** Replaces the settings; 409 if `basedOnVersion` is stale. Changing them needs no confirmation. */
    @PutMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.CONFLICT)
    fun updateApplicationSettings(
        @RequestBody request: ApplicationSettingsRequest,
    ): ApplicationSettingsResponse =
        ApplicationSettingsResponse.from(
            updateSettings.execute(request.toInput(), request.basedOnVersion, Actor.User).orThrow(),
        )
}
