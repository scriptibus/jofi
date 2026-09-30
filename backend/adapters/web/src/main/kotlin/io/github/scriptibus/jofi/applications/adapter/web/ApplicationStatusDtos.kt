// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.StatusChange
import io.github.scriptibus.jofi.applications.domain.StatusChangeInput
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Instant

// Reasons are the user's own words: DTOs that hold them print none of it.

/**
 * Body of `PUT /api/applications/{id}/status`: move to [status] (ADR-0044), optionally saying why
 * ([reason], Markdown). `DECLINED` and `REJECTED` need a [declineCategory] and become the application's
 * decline reason with [reason]; other statuses take none. [basedOnVersion] is the `version` last read.
 */
data class ChangeApplicationStatusRequest(
    val status: PipelineStatus,
    val reason: String? = null,
    val declineCategory: DeclineReasonCategory? = null,
    val basedOnVersion: Long,
) {
    fun toInput(): StatusChangeInput = StatusChangeInput(status.mapByName(), reason, declineCategory?.mapByName())

    override fun toString(): String =
        "ChangeApplicationStatusRequest(status=$status, declineCategory=$declineCategory, " +
            "basedOnVersion=$basedOnVersion)"
}

/** Who made a change; [name] names a scanner, an external client or a rule or job, and is absent otherwise. */
data class ChangeActorDto(
    val kind: ChangeActorKind,
    val name: String?,
) {
    companion object {
        fun from(actor: Actor): ChangeActorDto =
            when (actor) {
                Actor.User -> ChangeActorDto(ChangeActorKind.USER, null)
                Actor.Ai -> ChangeActorDto(ChangeActorKind.AI, null)
                is Actor.Scanner -> ChangeActorDto(ChangeActorKind.SCANNER, actor.name)
                is Actor.ExternalClient -> ChangeActorDto(ChangeActorKind.EXTERNAL_CLIENT, actor.name)
                is Actor.System -> ChangeActorDto(ChangeActorKind.SYSTEM, actor.name)
            }
    }
}

/**
 * One entry of the status history: [actor] moved the application [from] one status [to] another [at].
 * [from] is absent for the first entry (the status it started in). [reason] is Markdown; render it sanitised.
 */
data class StatusChangeResponse(
    val from: PipelineStatus?,
    val to: PipelineStatus,
    val reason: String?,
    val declineCategory: DeclineReasonCategory?,
    val actor: ChangeActorDto,
    val at: Instant,
) {
    override fun toString(): String = "StatusChangeResponse(from=$from, to=$to, at=$at)"

    companion object {
        fun from(change: StatusChange): StatusChangeResponse =
            StatusChangeResponse(
                change.from?.mapByName(),
                change.to.mapByName(),
                change.reason,
                change.declineCategory?.mapByName(),
                ChangeActorDto.from(change.actor),
                change.at,
            )
    }
}

/** JSON body of `GET /api/applications/{id}/status-history`: every status change, oldest first. */
data class StatusHistoryResponse(
    val changes: List<StatusChangeResponse>,
) {
    companion object {
        fun from(changes: List<StatusChange>): StatusHistoryResponse =
            StatusHistoryResponse(changes.map(StatusChangeResponse::from))
    }
}
