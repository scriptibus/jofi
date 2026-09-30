// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSettingsRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.UpdateApplicationSettingsPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSettings
import io.github.scriptibus.jofi.applications.domain.ApplicationSettingsInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Clock

/**
 * Replaces the application settings (ADR-0050). The version is checked first (even for unchanged values), then the
 * input; a change is stored with its changelog entry, which records the values before and after (they are not
 * personal), in one transaction. Unchanged values store and record nothing.
 */
class UpdateApplicationSettingsUseCase(
    private val settings: ApplicationSettingsRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateApplicationSettingsPort {
    override fun execute(
        input: ApplicationSettingsInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<ApplicationSettings> =
        transactions.inApplicationTransaction {
            settings
                .find()
                .toResult()
                .then { current -> current.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { values -> edit(current, values, actor) } }
        }

    private fun edit(
        current: ApplicationSettings,
        values: ApplicationSettings.Values,
        actor: Actor,
    ): ApplicationResult<ApplicationSettings> {
        val changed = current.edit(values, clock.storedNow())
        val changedAt = changed.updatedAt
        if (changed == current || changedAt == null) return ApplicationResult.Success(current)
        return settings.update(changed).toResult().then {
            val recorded =
                changelog.record(
                    ApplicationSettings.ENTITY_REF,
                    actor,
                    changedAt,
                    "Changed application settings",
                    changes(current.values, values),
                )
            changed.applicationIf(recorded, "changelog")
        }
    }

    private fun changes(
        before: ApplicationSettings.Values,
        after: ApplicationSettings.Values,
    ): List<FieldChange> =
        listOfNotNull(
            change("ghostedAfterWeeks", before.ghostedAfterWeeks, after.ghostedAfterWeeks),
            change("followUpAfterDays", before.followUpAfterDays, after.followUpAfterDays),
        )

    private fun change(
        field: String,
        before: Int,
        after: Int,
    ): FieldChange? = if (before == after) null else FieldChange(field, before.toString(), after.toString())

    private fun ApplicationSettings.basedOn(version: Long): ApplicationResult<ApplicationSettings> =
        if (this.version == version) ApplicationResult.Success(this) else ApplicationResult.VersionConflict
}
