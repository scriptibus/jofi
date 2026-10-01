// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application

import io.github.scriptibus.jofi.applications.application.port.PostingExtractionPort
import io.github.scriptibus.jofi.applications.application.port.PostingImportRepositoryPort
import io.github.scriptibus.jofi.applications.application.port.inbound.AddDiscoveredApplicationPort
import io.github.scriptibus.jofi.applications.application.port.inbound.RunPostingImportPort
import io.github.scriptibus.jofi.applications.domain.ApplicationInput
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.CompanyRef
import io.github.scriptibus.jofi.applications.domain.ExtractedPosting
import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.ImportStatus
import io.github.scriptibus.jofi.applications.domain.PostingExtraction
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.companies.application.port.api.MatchCompanyPort
import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import io.github.scriptibus.jofi.shared.domain.Actor
import java.time.Clock

/**
 * Runs a pending posting import (#96, the worker job). The model's answer is as untrusted as the posting: it only ever
 * fills the fields of a new `DISCOVERED` application ([ExtractedPosting.checked] validates them), never a status, a
 * tool call or anything else. Steps: extraction; the company matched or created by name ([MatchCompanyPort], its own
 * transaction: a company created for an import that then fails stays and is matched again by the next attempt); then
 * one transaction with the application ([AddDiscoveredApplicationPort]) and the import marked done, which stores
 * only if the import is still at this attempt. Failures of the extraction or the answer mark the import failed, and so
 * does anything a retry cannot fix; storage failures are retried by the job until the import has stalled
 * ([PostingImport.STALLED_AFTER]), then it is marked failed (`NOT_COMPLETED`) as well.
 */
class RunPostingImportUseCase(
    private val imports: PostingImportRepositoryPort,
    private val extraction: PostingExtractionPort,
    private val companies: MatchCompanyPort,
    private val discovered: AddDiscoveredApplicationPort,
    private val changelog: ChangelogPort,
    private val transactions: TransactionPort,
    private val clock: Clock,
) : RunPostingImportPort {
    override fun execute(
        id: ImportId,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        imports.findById(id).importResult().then { current ->
            // Only a succeeded import has no text; a done or failed one is answered as it is (a repeated job run).
            val text = current.text
            if (current.status != ImportStatus.PENDING || text == null) {
                ApplicationResult.Success(current)
            } else {
                when (val read = extraction.extract(text)) {
                    is PostingExtraction.Failed -> fail(current, read.reason, actor)
                    is PostingExtraction.Extracted -> create(current, read.posting, actor)
                }
            }
        }

    private fun create(
        current: PostingImport,
        posting: ExtractedPosting,
        actor: Actor,
    ): ApplicationResult<PostingImport> {
        // Checked before any company is matched, so an answer that is no posting creates no company either.
        val checked = posting.checked() ?: return fail(current, ImportFailure.NOT_A_POSTING, actor)
        return when (val match = companies.execute(checked.company, actor)) {
            is MatchCompanyPort.Match.Found -> {
                store(current, checked.toInput(CompanyRef(match.id)), actor)
            }

            is MatchCompanyPort.Match.Created -> {
                store(current, checked.toInput(CompanyRef(match.id)), actor)
            }

            MatchCompanyPort.Match.InvalidName -> {
                fail(current, ImportFailure.NOT_A_POSTING, actor)
            }

            MatchCompanyPort.Match.Unavailable -> {
                retryOrGiveUp(
                    current,
                    ApplicationResult.StorageFailure("company"),
                    actor,
                )
            }
        }
    }

    private fun store(
        current: PostingImport,
        input: ApplicationInput,
        actor: Actor,
    ): ApplicationResult<PostingImport> {
        val created =
            transactions.inApplicationTransaction {
                discovered.execute(input, current.toSourceInput(), actor).then { application ->
                    imports.transition(changelog, current, current.succeeded(application.id, clock.storedNow()), actor)
                }
            }
        return when (created) {
            is ApplicationResult.Success, ApplicationResult.VersionConflict, ApplicationResult.ImportNotFound -> created

            // The company was deleted meanwhile, or the input broke a rule `checked` does not know: no application.
            is ApplicationResult.Invalid -> fail(current, ImportFailure.NOT_A_POSTING, actor)

            is ApplicationResult.StorageFailure -> retryOrGiveUp(current, created, actor)

            // Nothing a retry could change: the import must not stay pending (and keep its text) forever.
            else -> fail(current, ImportFailure.NOT_COMPLETED, actor)
        }
    }

    /**
     * A storage failure the job retries, until the import has [PostingImport.stalled]: then the run gives up and marks
     * it failed, so the user sees it and can retry; if even that cannot be stored, the job tries again later.
     */
    private fun retryOrGiveUp(
        current: PostingImport,
        failure: ApplicationResult.StorageFailure,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        if (current.stalled(clock.storedNow())) fail(current, ImportFailure.NOT_COMPLETED, actor) else failure

    private fun fail(
        current: PostingImport,
        reason: ImportFailure,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        transactions.inApplicationTransaction {
            imports.transition(changelog, current, current.failed(reason, clock.storedNow()), actor)
        }
}
