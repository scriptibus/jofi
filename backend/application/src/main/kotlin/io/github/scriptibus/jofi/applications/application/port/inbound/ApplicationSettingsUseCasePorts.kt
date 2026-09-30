// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port.inbound

import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationSettingsInput
import io.github.scriptibus.jofi.shared.domain.Actor

// Inbound ports for the application settings (#81), implemented by the use cases of the same name (#85).

/**
 * The settings, [ApplicationSettings.DEFAULT] until the user changes them. Reads only. Other contexts never use it:
 * the Ghosted suggestion (#85) gets its candidates, period applied, through the named interface `api`
 * (`FindGhostedCandidatesPort`), and the follow-up suggestions of the tasks context (#95) reach their period through
 * that named interface as well, since contexts never use each other's internals.
 */
interface GetApplicationSettingsPort {
    fun execute(): ApplicationResult<ApplicationSettings>
}

/**
 * Replaces the settings (a value out of its bounds is `Invalid`, OUT_OF_RANGE). `basedOnVersion` is the version
 * last read (0 for the defaults): a stale one is `VersionConflict`, checked first. Unchanged values store nothing
 * and write no changelog entry; a change writes one ([ApplicationSettings.ENTITY_REF]) with the values before and
 * after and the acting [actor].
 */
interface UpdateApplicationSettingsPort {
    fun execute(
        input: ApplicationSettingsInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<ApplicationSettings>
}
