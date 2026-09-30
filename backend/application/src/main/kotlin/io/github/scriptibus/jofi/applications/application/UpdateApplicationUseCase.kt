// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.UpdateApplicationPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationDetails
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Replaces all details of an application. The version is checked first; unchanged details store nothing
 * and write no changelog entry. It stores through `updateDetails`, which never writes the status, the
 * unread flag, the scores or the contact links, so a concurrent change of those is not lost; the
 * repository's version check catches a detail edit that slipped in between read and write.
 */
class UpdateApplicationUseCase(
    private val applications: ApplicationRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : UpdateApplicationPort {
    override fun execute(
        id: ApplicationId,
        input: ApplicationInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application> =
        transactions.inApplicationTransaction {
            applications
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { edit(current, it, actor) } }
        }

    private fun edit(
        current: Application,
        details: ApplicationDetails,
        actor: Actor,
    ): ApplicationResult<Application> {
        val edited = current.edit(details, clock.storedNow())
        if (edited == current) return ApplicationResult.Success(current)
        return applications.updateDetails(edited).toResult().then {
            val recorded =
                changelog.record(
                    edited.id.toEntityRef(),
                    actor,
                    edited.updatedAt,
                    describe("Edited application", current.details, details),
                    detailChanges(current.details, details),
                )
            edited.applicationIf(recorded, "changelog")
        }
    }
}
