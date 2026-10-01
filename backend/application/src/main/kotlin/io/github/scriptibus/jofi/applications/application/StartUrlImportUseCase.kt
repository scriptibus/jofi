// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ResolveUrlImportPort
import io.github.scriptibus.jofi.applications.application.port.inbound.StartUrlImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DisallowedPostingHosts
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.applications.domain.normalizedForImport
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.KeyedLockPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.time.Clock

/**
 * Starts importing a posting fetched from a URL (spec §8.1, #97): the link normalised (tracking parameters
 * stripped) and checked against sites Jofi never scrapes ([DisallowedPostingHosts]), then [ResolveUrlImportPort]
 * decides what it comes to while this use case holds the link's lock ([KeyedLockPort]): a double submit (#187
 * finding F6) waits for the first request instead of fetching and importing again, and answers with its pending
 * import or application. The wait costs a thread, not a database connection: nothing holds a connection (or a
 * database lock) while the page is fetched, which the guard bounds to 20 seconds, so a burst of slow imports cannot
 * starve the connection pool. A new import's job is queued after its transaction committed, as
 * [StartPostingImportUseCase]; an import whose job cannot be queued is stored as failed (`NOT_QUEUED`).
 */
class StartUrlImportUseCase(
    private val resolve: ResolveUrlImportPort,
    private val locks: KeyedLockPort,
    private val imports: PostingImportRepositoryPort,
    private val jobs: JobSchedulerPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : StartUrlImportPort {
    override fun execute(
        url: String,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        parsed(url)
            .then { address -> locks.withLock("url:${address.value}") { resolve.execute(address, actor) } }
            .then { outcome ->
                if (outcome is UrlImportOutcome.Started) {
                    jobs.queue(outcome.import) { notQueued(it, actor) }.then { ApplicationResult.Success(outcome) }
                } else {
                    ApplicationResult.Success(outcome)
                }
            }

    private fun notQueued(
        pending: PostingImport,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        transactions.inApplicationTransaction {
            imports.transition(changelog, pending, pending.failed(ImportFailure.NOT_QUEUED, clock.storedNow()), actor)
        }

    private fun parsed(url: String): ApplicationResult<WebAddress> {
        val address =
            WebAddress.parse(url.trim())?.normalizedForImport()
                ?: return invalid(ApplicationProblem.INVALID_URL)
        return if (DisallowedPostingHosts.isDisallowed(address)) {
            invalid(ApplicationProblem.NOT_ALLOWED)
        } else {
            ApplicationResult.Success(address)
        }
    }

    private fun <T> invalid(problem: ApplicationProblem): ApplicationResult<T> =
        ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.SOURCE_URL, problem)))
}
