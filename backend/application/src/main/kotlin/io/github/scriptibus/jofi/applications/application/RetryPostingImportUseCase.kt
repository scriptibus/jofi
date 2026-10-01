// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.RetryPostingImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Retries a failed or stalled posting import with its kept text (#96): pending again as its next attempt, with a
 * changelog entry, committed before the job is queued. Two retries at once store only one (the attempt is checked on
 * write); the other is a `VersionConflict`.
 */
class RetryPostingImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val ai: CheckAiTaskAssignedPort,
    private val jobs: JobSchedulerPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : RetryPostingImportPort {
    override fun execute(
        id: ImportId,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        imports
            .findById(id)
            .importResult()
            .then { current ->
                val next = current.retried(clock.storedNow()) ?: return@then ApplicationResult.ImportNotRetryable
                ai.extractionAssigned().then {
                    transactions.inApplicationTransaction { imports.transition(changelog, current, next, actor) }
                }
            }.then { pending -> jobs.queue(pending) { notQueued(it, actor) } }

    private fun notQueued(
        pending: PostingImport,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        transactions.inApplicationTransaction {
            imports.transition(changelog, pending, pending.failed(ImportFailure.NOT_QUEUED, clock.storedNow()), actor)
        }
}
