// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.AddApplicationSourcePort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.SourceInput
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Adds a place the job was found (#96, ADR-0046): the input, then in one transaction the application, the source with
 * its first description snapshot (frozen at once if the application is applied to already) and a changelog entry for
 * each. Not a new version of the application.
 */
class AddApplicationSourceUseCase(
    private val applications: ApplicationRepositoryPort,
    private val sources: ApplicationSourceRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : AddApplicationSourcePort {
    override fun execute(
        id: ApplicationId,
        input: SourceInput,
        actor: Actor,
    ): ApplicationResult<ApplicationSource> {
        val now = clock.storedNow()
        return input.validate(now).toResult().then { draft ->
            transactions.inApplicationTransaction {
                applications.findById(id).toResult().then { sources.addWithDiscovery(changelog, it, draft, actor, now) }
            }
        }
    }
}
