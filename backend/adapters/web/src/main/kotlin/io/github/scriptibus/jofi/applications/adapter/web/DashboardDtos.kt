// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.ActivityEntry
import io.github.scriptibus.jofi.applications.domain.ApplicationFunnel
import io.github.scriptibus.jofi.applications.domain.PipelineOverview
import java.time.Instant
import java.util.UUID

/** How many applications are in [status] now. */
data class StatusCountDto(
    val status: PipelineStatus,
    val count: Long,
)

/**
 * The funnel over the status history (ADR-0052): applications that ever reached applied, an interview and an offer,
 * and the applied ones the company answered (an interview, an offer or a rejection). The rates are shares from 0 to
 * 1, absent while their base is zero: [interviewRate] of the applied, [offerRate] of the interviewed, [responseRate]
 * of the applied.
 */
data class FunnelDto(
    val applied: Long,
    val interviewed: Long,
    val offered: Long,
    val responded: Long,
    val interviewRate: Double?,
    val offerRate: Double?,
    val responseRate: Double?,
) {
    companion object {
        fun from(funnel: ApplicationFunnel): FunnelDto =
            FunnelDto(
                funnel.applied,
                funnel.interviewed,
                funnel.offered,
                funnel.responded,
                funnel.interviewRate,
                funnel.offerRate,
                funnel.responseRate,
            )
    }
}

/** The dashboard's pipeline: every status in pipeline order (zero ones too), the unread count and the funnel. */
data class PipelineOverviewResponse(
    val byStatus: List<StatusCountDto>,
    val unread: Long,
    val funnel: FunnelDto,
) {
    companion object {
        fun from(overview: PipelineOverview): PipelineOverviewResponse =
            PipelineOverviewResponse(
                overview.byStatus.map { (status, count) -> StatusCountDto(status.mapByName(), count) },
                overview.unread,
                FunnelDto.from(overview.funnel),
            )
    }
}

/** The application an activity entry is about, labelled with its job title. */
data class ActivityApplicationDto(
    val id: UUID,
    val title: String,
)

/**
 * One changelog entry: [actor] changed the entity [entityType] / [entityId] (`application`, `interview`,
 * `application_source`, `description_snapshot`, `company`, `contact`, `task` or `countdown`). [description] is a fixed
 * English text (e.g. "Created application"); [fields] names the changed fields without their values. [application]
 * is set for an entry about an application or something of it, while the application exists.
 */
data class ActivityEntryResponse(
    val id: Long,
    val occurredAt: Instant,
    val actor: ChangeActorDto,
    val entityType: String,
    val entityId: String,
    val description: String,
    val fields: List<String>,
    val application: ActivityApplicationDto?,
) {
    companion object {
        fun from(entry: ActivityEntry): ActivityEntryResponse =
            ActivityEntryResponse(
                id = entry.id,
                occurredAt = entry.occurredAt,
                actor = ChangeActorDto.from(entry.actor),
                entityType = entry.entity.type,
                entityId = entry.entity.id,
                description = entry.description,
                fields = entry.fields,
                application = entry.application?.let { ActivityApplicationDto(it.id.value, it.title) },
            )
    }
}

/** The recent activity, newest first. */
data class RecentActivityResponse(
    val entries: List<ActivityEntryResponse>,
) {
    companion object {
        fun from(entries: List<ActivityEntry>): RecentActivityResponse =
            RecentActivityResponse(entries.map(ActivityEntryResponse::from))
    }
}
