// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.FetchPostingTextPort
import io.github.scriptibus.jofi.applications.application.port.inbound.StartUrlImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DisallowedPostingHosts
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.applications.domain.normalizedForImport
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.time.Clock
import java.util.UUID

/**
 * Starts importing a posting fetched from a URL (spec §8.1, #97): the link normalised (tracking parameters
 * stripped) and checked against sites Jofi never scrapes ([DisallowedPostingHosts]), then, in one transaction that
 * holds a lock on the link: a pending import for it answers with that import (double submit, #187 finding F6; the
 * lock makes a concurrent second request wait and find it, instead of fetching and importing again); a link
 * already imported successfully answers with its existing application; otherwise [FetchPostingTextPort] fetches
 * and extracts, and the same pending-import path as [StartPostingImportUseCase] stores it with its changelog entry.
 * The job is queued after the commit.
 *
 * The transaction (and its database connection) stays open during the fetch, which the guard bounds to 20 seconds;
 * Jofi has one user, so that costs nothing, and a lock held only in memory would not survive a second instance.
 */
class StartUrlImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val sources: ApplicationSourceRepositoryPort,
    private val fetch: FetchPostingTextPort,
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
            .then { address -> transactions.inApplicationTransaction { begin(address, actor) } }
            .then { (outcome, isNew) ->
                if (isNew) {
                    jobs
                        .queue(
                            outcome.import,
                        ) { notQueued(it, actor) }
                        .then { ApplicationResult.Success(outcome) }
                } else {
                    ApplicationResult.Success(outcome)
                }
            }

    private fun begin(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<Pair<UrlImportOutcome, Boolean>> =
        imports.lockForStart("url:${address.value}").toResult().then {
            imports.findPendingBySourceUrl(address).toResult().then { pending ->
                pending?.let { ApplicationResult.Success(UrlImportOutcome.Started(it) to false) }
                    ?: alreadyImportedOrFetch(address, actor)
            }
        }

    private fun alreadyImportedOrFetch(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<Pair<UrlImportOutcome, Boolean>> =
        sources.findByOriginalUrl(address).toResult().then { found ->
            found.firstOrNull()?.let { recordAlreadyImported(it.application, address, actor) }
                ?: fetchAndStore(address, actor)
        }

    private fun recordAlreadyImported(
        application: ApplicationId,
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<Pair<UrlImportOutcome, Boolean>> {
        val found = PostingImport.alreadyImported(ImportId(UUID.randomUUID()), application, address, clock.storedNow())
        return imports
            .addWithChangelog(changelog, found, actor)
            .then { ApplicationResult.Success(UrlImportOutcome.AlreadyImported(it) to false) }
    }

    private fun fetchAndStore(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<Pair<UrlImportOutcome, Boolean>> =
        fetch.execute(address).then { description ->
            val started = PostingImport.start(ImportId(UUID.randomUUID()), description, clock.storedNow(), address)
            imports
                .addWithChangelog(changelog, started, actor)
                .then { ApplicationResult.Success(UrlImportOutcome.Started(it) to true) }
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
