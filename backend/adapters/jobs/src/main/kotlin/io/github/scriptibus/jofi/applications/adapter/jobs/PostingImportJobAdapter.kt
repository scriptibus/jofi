// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.jobs

import io.github.scriptibus.jofi.applications.application.RunPostingImportUseCase
import io.github.scriptibus.jofi.applications.domain.ApplicationResult
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.application.port.JobHandlerPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.job.FailureReason
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.shared.domain.job.JobType
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Runs a posting import in the worker (#96). What it creates comes from the AI's reading of the posting, so its
 * changes are recorded as [Actor.Ai] (the user started the import; that step is recorded as theirs). A failed
 * extraction is a finished run (the import shows the failure and the user retries it); only storage failures are
 * retried by the job.
 */
@Component
class PostingImportJobAdapter(
    private val run: RunPostingImportUseCase,
) : JobHandlerPort {
    override val type: JobType = PostingImport.JOB_TYPE

    override fun run(arguments: Map<String, String>): JobOutcome {
        val id = arguments[PostingImport.JOB_ARGUMENT]?.let(::uuidOrNull) ?: return JobOutcome.GiveUp(INVALID_ARGUMENTS)
        return when (run.execute(ImportId(id), Actor.Ai)) {
            is ApplicationResult.Success -> JobOutcome.Done

            // Another run of this import stored its outcome first.
            ApplicationResult.VersionConflict -> JobOutcome.Done

            ApplicationResult.ImportNotFound -> JobOutcome.GiveUp(IMPORT_GONE)

            is ApplicationResult.StorageFailure -> JobOutcome.Retry(STORAGE_FAILURE)

            else -> JobOutcome.GiveUp(UNEXPECTED_RESULT)
        }
    }

    private fun uuidOrNull(text: String): UUID? =
        try {
            UUID.fromString(text)
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        val INVALID_ARGUMENTS = FailureReason("invalid-arguments")
        val IMPORT_GONE = FailureReason("import-gone")
        val STORAGE_FAILURE = FailureReason("storage-failure")
        val UNEXPECTED_RESULT = FailureReason("unexpected-result")
    }
}
