// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.mcp

import io.github.scriptibus.jofi.applications.domain.ImportFailure
import io.github.scriptibus.jofi.applications.domain.ImportStatus
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.applications.domain.UrlImportOutcome
import java.time.Instant
import java.util.UUID

/**
 * A posting import for polling: `PENDING` until the worker has read the posting, then `SUCCEEDED` with the new
 * application's [applicationId] (read it with `get_application`), or `FAILED` with a [failure] reason. It holds no
 * text of the posting or its link, so nothing third-party needs the untrusted mark here; the application it created
 * does, in `get_application`.
 */
data class PostingImportResult(
    val id: UUID,
    val status: ImportStatus,
    val failure: ImportFailure?,
    val applicationId: UUID?,
    val attempt: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(import: PostingImport) =
            PostingImportResult(
                import.id.value,
                import.status,
                import.failure,
                import.application?.value,
                import.attempt,
                import.createdAt,
                import.updatedAt,
            )
    }
}

/** What starting a URL import came to. */
enum class UrlImportKind {
    /** A new import was started (or a stalled one resumed): poll [PostingImportResult] until it is done. */
    STARTED,

    /** The same link is being imported already: this is that import. */
    ALREADY_PENDING,

    /** The link was imported before: the import already carries the existing application. */
    ALREADY_IMPORTED,
}

data class UrlImportResult(
    val outcome: UrlImportKind,
    val import: PostingImportResult,
) {
    companion object {
        fun from(outcome: UrlImportOutcome) =
            UrlImportResult(
                when (outcome) {
                    is UrlImportOutcome.Started -> UrlImportKind.STARTED
                    is UrlImportOutcome.AlreadyPending -> UrlImportKind.ALREADY_PENDING
                    is UrlImportOutcome.AlreadyImported -> UrlImportKind.ALREADY_IMPORTED
                },
                PostingImportResult.from(outcome.import),
            )
    }
}
