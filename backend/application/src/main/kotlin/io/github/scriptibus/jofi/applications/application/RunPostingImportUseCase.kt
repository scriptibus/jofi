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
 * fills the fields of a new `DISCOVERED` application ([ExtractedPosting.toInput] validates them), never a status, a
 * tool call or anything else. Steps: extraction; the company matched or created by name ([MatchCompanyPort], its own
 * transaction: a company created for an import that then fails stays and is matched again by the next attempt); then
 * one transaction with the application ([AddDiscoveredApplicationPort]) and the import marked done, which stores
 * only if the import is still at this attempt. Failures of the extraction or the answer mark the import failed.
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
        val match = posting.company?.let { companies.execute(it, actor) }
        val input = match?.id?.let { posting.toInput(CompanyRef(it)) }
        return when {
            match == MatchCompanyPort.Match.Unavailable -> ApplicationResult.StorageFailure("company")
            input == null -> fail(current, ImportFailure.NOT_A_POSTING, actor)
            else -> store(current, input, actor)
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
        // The company was deleted meanwhile, or the input broke a rule `toInput` does not know: no application.
        return if (created is ApplicationResult.Invalid) fail(current, ImportFailure.NOT_A_POSTING, actor) else created
    }

    private fun fail(
        current: PostingImport,
        reason: ImportFailure,
        actor: Actor,
    ): ApplicationResult<PostingImport> =
        transactions.inApplicationTransaction {
            imports.transition(changelog, current, current.failed(reason, clock.storedNow()), actor)
        }
}
