// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.FetchPostingTextPort
import io.github.scriptibus.jofi.applications.application.port.inbound.ResolveUrlImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.time.Clock
import java.util.UUID

/**
 * Decides what a URL import of one normalised link comes to (#97), without holding a database connection while it
 * fetches: first, plain reads for a pending import (answered as it is, [UrlImportOutcome.AlreadyPending]; one that
 * stalled, [PostingImport.STALLED_AFTER], is asked for again as its next attempt like a retry and its job queued
 * again, [UrlImportOutcome.Started], so the late original job and the new one still make one application) or an
 * application that already has the link (a short transaction records the attempt, [UrlImportOutcome.AlreadyImported]);
 * otherwise [FetchPostingTextPort] fetches with no transaction open, and a second short transaction stores the text
 * as a new pending import with its changelog entry. Nothing is stored before the fetch, so a failed fetch or a crash
 * leaves nothing to release. The caller serialises calls per link ([StartUrlImportUseCase]), so a double submit waits
 * instead of fetching twice; the store step takes the database lock for the link and looks once more for a pending
 * import and an imported application, which also keeps a second app instance from storing a second import.
 */
class ResolveUrlImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val sources: ApplicationSourceRepositoryPort,
    private val fetch: FetchPostingTextPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : ResolveUrlImportPort {
    override fun execute(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        imports.findPendingBySourceUrl(address).toResult().then { pending ->
            if (pending != null) {
                answerPending(pending, actor)
            } else {
                sources.findByOriginalUrl(address).toResult().then { found ->
                    found.firstOrNull()?.let { recordAlreadyImported(it.application, address, actor) }
                        ?: fetchAndStore(address, actor)
                }
            }
        }

    private fun answerPending(
        pending: PostingImport,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> {
        val again = pending.retried(clock.storedNow()).takeIf { pending.stalled(clock.storedNow()) }
        return if (again == null) {
            ApplicationResult.Success(UrlImportOutcome.AlreadyPending(pending))
        } else {
            transactions
                .inApplicationTransaction { imports.transition(changelog, pending, again, actor) }
                .then { ApplicationResult.Success(UrlImportOutcome.Started(it)) }
        }
    }

    private fun recordAlreadyImported(
        application: ApplicationId,
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        transactions.inApplicationTransaction { alreadyImported(application, address, actor) }

    private fun alreadyImported(
        application: ApplicationId,
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> {
        val found = PostingImport.alreadyImported(ImportId(UUID.randomUUID()), application, address, clock.storedNow())
        return imports
            .addWithChangelog(changelog, found, actor)
            .then { ApplicationResult.Success(UrlImportOutcome.AlreadyImported(it)) }
    }

    private fun fetchAndStore(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        fetch.execute(address).then { description ->
            transactions.inApplicationTransaction { storeUnlessKnown(address, description, actor) }
        }

    /** Under the database lock for the link, once more: a pending import or an imported application wins. */
    private fun storeUnlessKnown(
        address: WebAddress,
        description: DescriptionText,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        imports.lockForStart("url:${address.value}").toResult().then {
            imports.findPendingBySourceUrl(address).toResult().then { pending ->
                if (pending != null) {
                    ApplicationResult.Success(UrlImportOutcome.AlreadyPending(pending))
                } else {
                    sources.findByOriginalUrl(address).toResult().then { found ->
                        found.firstOrNull()?.let { alreadyImported(it.application, address, actor) }
                            ?: store(address, description, actor)
                    }
                }
            }
        }

    private fun store(
        address: WebAddress,
        description: DescriptionText,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> {
        val started = PostingImport.start(ImportId(UUID.randomUUID()), description, clock.storedNow(), address)
        return imports
            .addWithChangelog(changelog, started, actor)
            .then { ApplicationResult.Success(UrlImportOutcome.Started(it)) }
    }
}
