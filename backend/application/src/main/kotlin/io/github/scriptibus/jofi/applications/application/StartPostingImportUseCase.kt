// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.StartPostingImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock
import java.util.UUID

/**
 * Starts a posting import (#96): the text in its stored form (as a description, at most `DescriptionText.MAX_LENGTH`),
 * then whether the extraction task has a model, then the pending import with its changelog entry, committed before the
 * job is queued. An import whose job cannot be queued is stored as failed (`NOT_QUEUED`), so the user can retry it.
 */
class StartPostingImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val ai: CheckAiTaskAssignedPort,
    private val jobs: JobSchedulerPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : StartPostingImportPort {
    override fun execute(
        text: String,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        DescriptionInput(text, SnapshotReason.DISCOVERY)
            .validate()
            .toResult()
            .then { description -> ai.extractionAssigned().then { ApplicationResult.Success(description) } }
            .then { description ->
                val started = PostingImport.start(ImportId(UUID.randomUUID()), description, clock.storedNow())
                transactions.inApplicationTransaction {
                    imports.add(started).toResult().then {
                        started.applicationIf(changelog.recordImport(null, started, actor), "changelog")
                    }
                }
            }.then { started -> jobs.queue(started) { notQueued(it, actor) } }

    private fun notQueued(
        pending: PostingImport,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        transactions.inApplicationTransaction {
            imports.transition(changelog, pending, pending.failed(ImportFailure.NOT_QUEUED, clock.storedNow()), actor)
        }
}
