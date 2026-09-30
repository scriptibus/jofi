// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.ProblemKind
import io.github.scriptibus.jofi.shared.adapter.web.ProblemResponses
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The application settings (spec §6.1, §6.2, ADR-0050): when Ghosted and a follow-up are suggested. The contract only
 * (#81): both operations answer `501 Not Implemented` until #85 injects the use cases and maps each
 * `ApplicationResult.Failure` with [ApplicationProblems.of].
 */
@Suppress("UnusedParameter")
@RestController
@RequestMapping("/api/applications/settings")
class ApplicationSettingsController {
    /** The settings; the defaults (14 weeks, 14 days, version 0) until the user changes them. */
    @GetMapping
    fun getApplicationSettings(): ApplicationSettingsResponse = throw notImplemented()

    /** Replaces the settings; 409 if `basedOnVersion` is stale. Changing them needs no confirmation. */
    @PutMapping
    @ProblemResponses(ProblemKind.INVALID_INPUT, ProblemKind.CONFLICT)
    fun updateApplicationSettings(
        @RequestBody request: ApplicationSettingsRequest,
    ): ApplicationSettingsResponse = throw notImplemented()

    private fun notImplemented(): ErrorResponseException {
        val problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Application settings are not available yet")
        return ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null)
    }
}
