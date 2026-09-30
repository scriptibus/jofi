// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.CreateApplicationPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Adds an application by hand (spec §6.1) in the initial status; the application, the first entry of its
 * status history and its changelog entry are stored together. A company that does not exist is `Invalid`
 * (COMPANY, NOT_FOUND), found by `application_company_fk`.
 */
class CreateApplicationUseCase(
    private val applications: ApplicationRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : CreateApplicationPort {
    override fun execute(
        input: ApplicationInput,
        actor: Actor,
    ): ApplicationResult<Application> =
        input.validate().toResult().then { details ->
            val application = Application.create(ApplicationId(UUID.randomUUID()), details, clock.storedNow())
            transactions.inApplicationTransaction {
                applications.add(application, StatusChange.initial(application, actor)).toResult().then {
                    val recorded =
                        changelog.record(
                            application.id.toEntityRef(),
                            actor,
                            application.createdAt,
                            describe("Created application", null, details),
                            detailChanges(null, details),
                        )
                    application.applicationIf(recorded, "changelog")
                }
            }
        }
}
