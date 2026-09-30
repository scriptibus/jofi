// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.DescriptionSnapshotRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ChangeApplicationStatusPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationStatusChanged
import io.github.scriptibus.jofi.applications.domain.StatusChangeInput
import io.github.scriptibus.jofi.applications.domain.StatusChangeRequest
import io.github.scriptibus.jofi.applications.domain.StatusTransition
import io.github.scriptibus.jofi.applications.domain.StatusTransition.Changed
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.DomainEventPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.FieldChange
import java.time.Clock

/**
 * Moves an application along the matrix of ADR-0044. In one transaction: the version check (first, even
 * for a no-op), the input, the move, the stored change with its history entry, the changelog entry, the
 * description freeze when the application is first applied to (ADR-0046) with one changelog entry per frozen
 * snapshot, and the published `ApplicationStatusChanged`. Any step failing rolls all of it back.
 *
 * The changelog names the field `status` (before, after) and, when the decline category changes, the field
 * `declineReason` (category before, after). A self-move corrects the decline reason ("Corrected decline
 * reason"): it records only `declineReason`, never a meaningless `status` DECLINED → DECLINED, and no field at
 * all when only the text changed. The reason's text never goes into the changelog; the history keeps it.
 */
class ChangeApplicationStatusUseCase(
    private val applications: ApplicationRepositoryPort,
    private val snapshots: DescriptionSnapshotRepositoryPort,
    private val events: DomainEventPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : ChangeApplicationStatusPort {
    override fun execute(
        id: ApplicationId,
        input: StatusChangeInput,
        basedOnVersion: Long,
        actor: Actor,
    ): ApplicationResult<Application> =
        transactions.inApplicationTransaction {
            applications
                .findById(id)
                .toResult()
                .then { it.basedOn(basedOnVersion) }
                .then { current -> input.validate().toResult().then { move(current, it, actor) } }
        }

    private fun move(
        current: Application,
        request: StatusChangeRequest,
        actor: Actor,
    ): ApplicationResult<Application> =
        when (val transition = current.changeStatus(request, actor, clock.storedNow())) {
            StatusTransition.Unchanged -> ApplicationResult.Success(current)
            is StatusTransition.NotAllowed -> ApplicationResult.InvalidTransition(transition.from, transition.to)
            is Changed -> store(current, transition, actor)
        }

    private fun store(
        current: Application,
        transition: Changed,
        actor: Actor,
    ): ApplicationResult<Application> {
        val moved = transition.application
        return applications
            .changeStatus(moved, transition.change)
            .toResult()
            .then {
                val recorded =
                    changelog.record(
                        moved.id.toEntityRef(),
                        actor,
                        moved.updatedAt,
                        describe(current, moved),
                        fields(current, moved),
                    )
                moved.applicationIf(recorded, "changelog")
            }.then { freezeIfApplied(transition.event) }
            .then { moved.applicationIf(events.publish(transition.event), "publish event") }
    }

    /** Freezes the descriptions on the first move into "applied" (ADR-0046); only the first freeze counts. */
    private fun freezeIfApplied(event: ApplicationStatusChanged): ApplicationResult<Unit> {
        if (!event.freezesDescriptions) return ApplicationResult.Success(Unit)
        return snapshots.freeze(event.application, event.occurredAt).toResult().then { frozen ->
            val recorded =
                frozen.all { snapshot ->
                    changelog.record(
                        snapshot.toEntityRef(),
                        event.actor,
                        event.occurredAt,
                        "Froze job description",
                        listOf(FieldChange("frozenAt", null, event.occurredAt.toString())),
                    )
                }
            Unit.applicationIf(recorded, "changelog")
        }
    }

    /** A self-move can only correct the decline reason (`Application.changeStatus` makes it a no-op otherwise). */
    private fun describe(
        current: Application,
        moved: Application,
    ): String = if (current.status == moved.status) "Corrected decline reason" else "Changed application status"

    private fun fields(
        current: Application,
        moved: Application,
    ): List<FieldChange> =
        listOfNotNull(
            changeOf("status", current.status, moved.status),
            changeOf(DECLINE_REASON, current.declineReason?.category, moved.declineReason?.category),
        )

    private companion object {
        /** The decline reason's field in the changelog: its category only, the text is the user's own words. */
        const val DECLINE_REASON = "declineReason"
    }
}
