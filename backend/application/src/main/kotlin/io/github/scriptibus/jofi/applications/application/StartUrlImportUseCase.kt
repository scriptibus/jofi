// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.ApplicationSourceRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.StartUrlImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationField
import io.github.scriptibus.jofi.applications.domain.ApplicationId
import io.github.scriptibus.jofi.applications.domain.ApplicationProblem
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ApplicationValidation
import io.github.scriptibus.jofi.applications.domain.ApplicationViolation
import io.github.scriptibus.jofi.applications.domain.DescriptionInput
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.DisallowedPostingHosts
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingHtmlText
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.SnapshotReason
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import io.github.scriptibus.jofi.applications.domain.normalizedForImport
import io.github.scriptibus.jofi.setup.application.port.api.CheckAiTaskAssignedPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.JobSchedulerPort
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.github.scriptibus.jofi.shared.domain.text.WebAddress
import java.time.Clock
import java.util.UUID

/**
 * Starts importing a posting fetched from a URL (spec §8.1, #97): the link normalised (tracking parameters
 * stripped), checked against sites Jofi never scrapes ([DisallowedPostingHosts]), then whether the extraction task
 * has a model (nothing is fetched otherwise). A link already imported successfully answers at once with its
 * existing application; one already pending answers with that import (double submit, #187 finding F6). Otherwise
 * fetched through [OutboundHttpPort] (the SSRF guard), its main text extracted ([PostingHtmlText]), then the same
 * path as [StartPostingImportUseCase]: a new pending import with its changelog entry, committed before the job is
 * queued.
 *
 * Eight constructor parameters: every port is its own real dependency (fetching, matching an existing
 * application and starting the import each need one, ADR-0034, ADR-0041); none bundles cleanly without
 * inventing a type that is not a use case, which `SourceConventionsTest` forbids in `application`.
 */
@Suppress("LongParameterList")
class StartUrlImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val sources: ApplicationSourceRepositoryPort,
    private val ai: CheckAiTaskAssignedPort,
    private val http: OutboundHttpPort,
    private val jobs: JobSchedulerPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : StartUrlImportPort {
    override fun execute(
        url: String,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        parsed(url).then { address -> ai.extractionAssigned().then { duplicateOrFetch(address, actor) } }

    private fun duplicateOrFetch(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        imports.findPendingBySourceUrl(address).toResult().then { pending ->
            pending?.let { ApplicationResult.Success(UrlImportOutcome.Started(it)) } ?: alreadyImportedOrFetch(
                address,
                actor,
            )
        }

    private fun alreadyImportedOrFetch(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        sources.findByOriginalUrl(address).toResult().then { found ->
            found.firstOrNull()?.let { recordAlreadyImported(it.application, address, actor) }
                ?: fetchAndStart(address, actor)
        }

    private fun recordAlreadyImported(
        application: ApplicationId,
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> {
        val found = PostingImport.alreadyImported(ImportId(UUID.randomUUID()), application, address, clock.storedNow())
        return transactions
            .inApplicationTransaction { imports.addWithChangelog(changelog, found, actor) }
            .then { ApplicationResult.Success(UrlImportOutcome.AlreadyImported(it)) }
    }

    private fun fetchAndStart(
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> =
        fetch(address).then { html ->
            toDescription(html).then { description -> startAndQueue(description, address, actor) }
        }

    private fun fetch(address: WebAddress): ApplicationResult<String> {
        val request = OutboundRequest(uri = address.toUri(), acceptedContentTypes = HTML_CONTENT_TYPES)
        return when (val result = http.fetch(request)) {
            is FetchResult.Success -> ApplicationResult.Success(String(result.resource.body.bytes(), Charsets.UTF_8))
            else -> invalid(ApplicationProblem.UNREACHABLE)
        }
    }

    private fun toDescription(html: String): ApplicationResult<DescriptionText> {
        val text = PostingHtmlText.extract(html).take(DescriptionText.MAX_LENGTH)
        return when (val validated = DescriptionInput(text, SnapshotReason.DISCOVERY).validate()) {
            is ApplicationValidation.Valid -> ApplicationResult.Success(validated.value)
            is ApplicationValidation.Invalid -> invalid(ApplicationProblem.UNREACHABLE)
        }
    }

    private fun startAndQueue(
        description: DescriptionText,
        address: WebAddress,
        actor: Actor,
    ): ApplicationResult<UrlImportOutcome> {
        val started = PostingImport.start(ImportId(UUID.randomUUID()), description, clock.storedNow(), address)
        return transactions
            .inApplicationTransaction { imports.addWithChangelog(changelog, started, actor) }
            .then { pending -> jobs.queue(pending) { notQueued(it, actor) } }
            .then { pending -> ApplicationResult.Success(UrlImportOutcome.Started(pending)) }
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
            WebAddress.parse(url.trim())?.normalizedForImport() ?: return invalid(ApplicationProblem.INVALID_URL)
        return if (DisallowedPostingHosts.isDisallowed(address)) {
            invalid(ApplicationProblem.NOT_ALLOWED)
        } else {
            ApplicationResult.Success(address)
        }
    }

    private fun <T> invalid(problem: ApplicationProblem): ApplicationResult<T> =
        ApplicationResult.Invalid(listOf(ApplicationViolation(ApplicationField.SOURCE_URL, problem)))

    private companion object {
        val HTML_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}
