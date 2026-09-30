// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.domain.Application
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationSource
import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionSnapshot
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SnapshotId
import io.github.scriptibus.jofi.applications.domain.SourceDraft
import io.github.scriptibus.jofi.applications.domain.SourceId
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort.Assignment
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.JobRequest
import io.github.scriptibus.jofi.shared.domain.job.JobResult
import java.time.Instant
import java.util.UUID

// Steps shared by the source and posting import use cases (#96).

/**
 * Stores [draft] as a new source of [application] with its first description snapshot, if it has a text, and a
 * changelog entry for each, in the caller's transaction. The limit is checked here ([Application.addSource]) and
 * again by the store under a lock.
 */
internal fun ApplicationSourceRepositoryPort.addWithDiscovery(
    changelog: ChangelogPort,
    application: Application,
    draft: SourceDraft,
    actor: Actor,
    now: Instant,
): ApplicationResult<ApplicationSource> {
    val source = draft.toSource(SourceId(UUID.randomUUID()), application.id)
    return application.addSource(source).toResult().then {
        val discovery =
            draft.description?.let { text ->
                DescriptionSnapshot.discovery(
                    SnapshotId(UUID.randomUUID()),
                    source.id,
                    text,
                    application,
                    now,
                )
            }
        add(source, discovery).toResult().then {
            val recorded =
                changelog.recordSource(source, actor, now) &&
                    (discovery == null || changelog.recordSnapshot(discovery, actor))
            source.applicationIf(recorded, "changelog")
        }
    }
}

/** An import read or written: not found means there is no such import. */
internal fun <T> ApplicationStoreResult<T>.importResult(): ApplicationResult<T> =
    if (this == ApplicationStoreResult.NotFound) ApplicationResult.ImportNotFound else toResult()

/** Stores the import's step from [current] to [next] with its changelog entry, in the caller's transaction. */
internal fun PostingImportRepositoryPort.transition(
    changelog: ChangelogPort,
    current: PostingImport,
    next: PostingImport,
    actor: Actor,
): ApplicationResult<PostingImport> =
    update(current, next).importResult().then {
        next.applicationIf(changelog.recordImport(current, next, actor), "changelog")
    }

/** Whether the extraction task has a model, so an import can run at all. */
internal fun CheckAiTaskAssignedPort.extractionAssigned(): ApplicationResult<Unit> =
    when (execute(AiTask.EXTRACTION)) {
        Assignment.Assigned -> ApplicationResult.Success(Unit)
        Assignment.NotAssigned -> ApplicationResult.AiNotConfigured
        Assignment.Unavailable -> ApplicationResult.StorageFailure("ai assignment")
    }

/**
 * Queues the job that runs [postingImport], after its transaction committed (ADR-0038: enqueueing is not part of
 * it). If the job store refuses, [notQueued] records that, so the import does not wait forever.
 */
internal fun JobSchedulerPort.queue(
    postingImport: PostingImport,
    notQueued: (PostingImport) -> ApplicationResult<PostingImport>,
): ApplicationResult<PostingImport> {
    val request =
        JobRequest(PostingImport.JOB_TYPE, mapOf(PostingImport.JOB_ARGUMENT to postingImport.id.value.toString()))
    return when (enqueue(request)) {
        is JobResult.Success -> ApplicationResult.Success(postingImport)
        else -> notQueued(postingImport)
    }
}
