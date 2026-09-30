// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.SetApplicationUnreadPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Clock

/**
 * Marks an application read or unread (ADR-0041): no version check and no new version, since opening an
 * application must never conflict with an edit. `setUnread` writes only the flag. A changed flag writes a
 * changelog entry; setting the flag it already has is a no-op.
 */
class SetApplicationUnreadUseCase(
    private val applications: ApplicationRepositoryPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : SetApplicationUnreadPort {
    override fun execute(
        id: ApplicationId,
        unread: Boolean,
        actor: Actor,
    ): ApplicationResult<Application> =
        transactions.inApplicationTransaction {
            applications.findById(id).toResult().then { current ->
                if (current.unread == unread) {
                    ApplicationResult.Success(current)
                } else {
                    mark(current, unread, actor)
                }
            }
        }

    private fun mark(
        current: Application,
        unread: Boolean,
        actor: Actor,
    ): ApplicationResult<Application> =
        applications.setUnread(current.id, unread).toResult().then {
            val recorded =
                changelog.record(
                    current.id.toEntityRef(),
                    actor,
                    clock.storedNow(),
                    if (unread) "Marked application unread" else "Marked application read",
                    listOf(FieldChange("unread", current.unread.toString(), unread.toString())),
                )
            current.markUnread(unread).applicationIf(recorded, "changelog")
        }
}
