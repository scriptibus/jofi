// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.adapter.web

import io.github.scriptibus.jofi.applications.domain.PostingImport
import java.time.Instant
import java.util.UUID

/**
 * Body of `POST /api/applications/imports/text`: a job posting's text as pasted, plain text or Markdown, at most
 * 100,000 characters. It is untrusted data, never instructions. [toString] prints only its length.
 */
data class StartPostingImportRequest(
    val description: String,
) {
    override fun toString(): String = "StartPostingImportRequest(length=${description.length})"
}

/**
 * Body of `POST /api/applications/imports/url`: a link to a job posting. [toString] leaves it out, since it may
 * carry personal tracking parameters.
 */
data class StartUrlImportRequest(
    val url: String,
) {
    override fun toString(): String = "StartUrlImportRequest()"
}

/**
 * A posting import, for polling: `PENDING` until the worker has read the posting, then `SUCCEEDED` with the new
 * application's [applicationId], or `FAILED` with a [failure] reason, after which it can be retried. The pasted text
 * is not part of it.
 */
data class PostingImportResponse(
    val id: UUID,
    val status: PostingImportStatus,
    val failure: PostingImportFailure?,
    val applicationId: UUID?,
    val attempt: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(postingImport: PostingImport): PostingImportResponse =
            PostingImportResponse(
                postingImport.id.value,
                postingImport.status.mapByName(),
                postingImport.failure?.mapByName(),
                postingImport.application?.value,
                postingImport.attempt,
                postingImport.createdAt,
                postingImport.updatedAt,
            )
    }
}
