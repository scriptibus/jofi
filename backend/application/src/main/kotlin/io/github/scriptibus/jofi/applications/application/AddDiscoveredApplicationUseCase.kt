// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.AddDiscoveredApplicationPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Creates a `DISCOVERED` application with where it was found (#96): both inputs are validated first, then in one
 * transaction (joining the caller's, if any) the application, unread, with its first history entry, its source with
 * the discovery snapshot, and a changelog entry for each.
 */
class AddDiscoveredApplicationUseCase(
    private val applications: ApplicationRepositoryPort,
    private val sources: ApplicationSourceRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : AddDiscoveredApplicationPort {
    override fun execute(
        input: ApplicationInput,
        source: SourceInput,
        actor: Actor,
    ): ApplicationResult<Application> {
        val now = clock.storedNow()
        return input.validate().toResult().then { details ->
            source.validate(now).toResult().then { draft ->
                val application = Application.create(ApplicationId(UUID.randomUUID()), details, now, unread = true)
                transactions.inApplicationTransaction {
                    applications
                        .add(application, StatusChange.initial(application, actor))
                        .toResult()
                        .then { application.applicationIf(changelog.recordCreated(application, actor), "changelog") }
                        .then { sources.addWithDiscovery(changelog, application, draft, actor, now) }
                        .then { added -> ApplicationResult.Success(application.copy(sources = listOf(added))) }
                }
            }
        }
    }
}
