// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupProblem
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponseException

/** Problem details of the backup endpoints (types `urn:jofi:problem:system:backup-*`). */
object BackupProblems {
    const val REFUSED = "urn:jofi:problem:system:backup-refused"
    const val NOT_FOUND = "urn:jofi:problem:system:backup-not-found"
    const val UNAVAILABLE = "urn:jofi:problem:system:backup-unavailable"
    const val BUSY = "urn:jofi:problem:system:backup-busy"
    const val INCOMPLETE = "urn:jofi:problem:system:backup-restore-incomplete"

    /** The problem for a failed export, `null` when it succeeded. */
    fun of(result: BackupExportResult): ErrorResponseException? =
        when (result) {
            is BackupExportResult.Exported -> null
            is BackupExportResult.PasswordRefused -> AuthProblems.of(result.check)
            BackupExportResult.Busy -> busy()
            BackupExportResult.Failed -> unavailable()
        }

    /** The problem for a restore that did not run or failed (not the confirmation or password steps). */
    fun of(result: BackupRestoreResult): ErrorResponseException? =
        when (result) {
            BackupRestoreResult.NotFound -> {
                AuthProblems.problem(
                    HttpStatus.NOT_FOUND,
                    NOT_FOUND,
                    "No uploaded backup with this id",
                )
            }

            is BackupRestoreResult.Refused -> {
                refused(result.problem)
            }

            BackupRestoreResult.Busy -> {
                busy()
            }

            BackupRestoreResult.StorageFailure -> {
                unavailable()
            }

            BackupRestoreResult.Inconsistent -> {
                incomplete()
            }

            else -> {
                null
            }
        }

    fun refused(problem: BackupProblem): ErrorResponseException {
        val status =
            when (problem) {
                BackupProblem.TOO_LARGE, BackupProblem.TOO_MANY_ENTRIES -> HttpStatus.CONTENT_TOO_LARGE
                else -> HttpStatus.UNPROCESSABLE_CONTENT
            }
        return AuthProblems.problem(status, REFUSED, "This backup cannot be restored: ${reasonOf(problem)}").apply {
            body.setProperty("reason", reasonOf(problem))
        }
    }

    fun busy(): ErrorResponseException =
        AuthProblems.problem(
            HttpStatus.CONFLICT,
            BUSY,
            "Another backup upload, restore or export is running; try again later",
        )

    fun unavailable(): ErrorResponseException =
        AuthProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "Backup storage failed; nothing was changed")

    private fun incomplete(): ErrorResponseException =
        AuthProblems.problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            INCOMPLETE,
            "The restore failed and could not be undone completely; Jofi finishes undoing it at the next start",
        )

    /** The `reason` member of a refusal, e.g. `schema-older`. */
    fun reasonOf(problem: BackupProblem): String = problem.name.lowercase().replace('_', '-')
}
