// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.system.application.ExportBackupUseCase
import io.github.scriptibus.jofi.system.application.RestoreBackupUseCase
import io.github.scriptibus.jofi.system.application.StageBackupUseCase
import io.github.scriptibus.jofi.system.domain.PasswordConfirmation
import io.github.scriptibus.jofi.system.domain.backup.BackupExportResult
import io.github.scriptibus.jofi.system.domain.backup.BackupId
import io.github.scriptibus.jofi.system.domain.backup.BackupRestoreResult
import io.github.scriptibus.jofi.system.domain.backup.BackupStageResult
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Backup and restore (spec §3.1, ADR-0042). **A backup grants full access**: it holds all data, the
 * password hash, and the stored secrets together with the master keyset that decrypts them. Export
 * and restore therefore need the current password (throttled like a login). Restoring replaces all
 * data, needs the two-step confirmation (ADR-0039) and ends every session. Only one upload or restore
 * runs at a time, never next to an export (`409 backup-busy`).
 */
@RestController
@RequestMapping("/api/system/backup")
class BackupController(
    private val export: ExportBackupUseCase,
    private val stage: StageBackupUseCase,
    private val restore: RestoreBackupUseCase,
) {
    /**
     * Streams a complete backup as a zip (`application/zip`, attachment) after checking the current
     * password; every export is in the changelog. It contains everything, the secrets and the key to
     * decrypt them included: store it like a password.
     */
    @PostMapping("/exports", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [ZIP])
    fun exportBackup(
        @RequestBody body: BackupPasswordRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val result = export.execute(confirmation(body, request)) { createdAt -> streamTo(response, createdAt) }
        // Once streaming started, the archive stays unfinished (no central directory, no manifest), so a
        // broken download is never a valid zip; before that, this is a clean problem.
        BackupProblems.of(result)?.let { throw it }
    }

    /**
     * Uploads a backup (`application/zip`) and checks it: size limits, paths, format, schema version,
     * tables, checksums, account and keyset; an older schema is migrated. Nothing is replaced yet;
     * restore it with [restoreBackup].
     */
    @PostMapping("/restores", consumes = [ZIP])
    @ResponseStatus(HttpStatus.CREATED)
    fun stageBackupRestore(
        @RequestBody upload: InputStreamResource,
    ): StagedBackupResponse =
        when (val result = upload.inputStream.use(stage::execute)) {
            is BackupStageResult.Staged -> StagedBackupResponse.from(result.backup)
            is BackupStageResult.Refused -> throw BackupProblems.refused(result.problem)
            BackupStageResult.Busy -> throw BackupProblems.busy()
            BackupStageResult.StorageFailure -> throw BackupProblems.unavailable()
        }

    /**
     * Replaces **all data** with an uploaded backup, after checking the current password. The first
     * call answers `428` with the confirmation token and the effect; the same call with the token in
     * `Jofi-Confirmation` restores. Every session ends: log in again with the backup's password.
     */
    @PostMapping("/restores/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restoreBackup(
        @PathVariable id: UUID,
        @RequestHeader(Confirmations.HEADER, required = false) confirmation: String?,
        @RequestBody body: BackupPasswordRequest,
        request: HttpServletRequest,
    ) {
        val result =
            restore.execute(
                BackupId(id),
                Confirmations.requester(request),
                confirmation(body, request),
                Confirmations.token(confirmation),
            )
        failureOf(result)?.let { throw it }
    }

    private fun failureOf(result: BackupRestoreResult): ErrorResponseException? =
        when (result) {
            is BackupRestoreResult.Restored -> null
            is BackupRestoreResult.NotConfirmed -> Confirmations.problem(result.outcome)
            is BackupRestoreResult.PasswordRefused -> AuthProblems.of(result.check)
            else -> BackupProblems.of(result)
        }

    private fun confirmation(
        body: BackupPasswordRequest,
        request: HttpServletRequest,
    ) = PasswordConfirmation(body.password, ClientAddress.throttleKey(request.remoteAddr))

    private fun streamTo(
        response: HttpServletResponse,
        createdAt: Instant,
    ) = response.run {
        contentType = ZIP
        setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val name = "jofi-backup-${FILE_TIME.format(createdAt)}.zip"
        setHeader(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition
                .attachment()
                .filename(name)
                .build()
                .toString(),
        )
        outputStream
    }

    companion object {
        const val ZIP = "application/zip"
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
