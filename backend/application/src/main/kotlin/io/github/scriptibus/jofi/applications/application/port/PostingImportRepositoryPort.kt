// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.applications.application.port

import io.github.scriptibus.jofi.applications.domain.ApplicationStoreResult
import io.github.scriptibus.jofi.applications.domain.DescriptionText
import io.github.scriptibus.jofi.applications.domain.ImportId
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.shared.domain.text.WebAddress

/**
 * Stores posting imports (table `posting_import`, #96, #97) with their pasted text or fetched link. The use case
 * appends the changelog entry (entity [ImportId.ENTITY_TYPE]; status, failure and attempt, never the text or the
 * link) in the same transaction. Implementations never throw and never log row data.
 */
interface PostingImportRepositoryPort {
    fun add(postingImport: PostingImport): ApplicationStoreResult<Unit>

    fun findById(id: ImportId): ApplicationStoreResult<PostingImport>

    /**
     * Stores [next] (status, failure, application, attempt, `updated_at`) only if the stored import still has
     * [current]'s status and attempt, so a stale job run or a double retry changes nothing:
     * [ApplicationStoreResult.VersionConflict]. [ApplicationStoreResult.NotFound] if the import is gone.
     */
    fun update(
        current: PostingImport,
        next: PostingImport,
    ): ApplicationStoreResult<Unit>

    /**
     * A pending import with exactly this pasted [text], if any (#187 finding F6): the user double-submitted the
     * same text before the first import finished, so the resubmit answers with that import instead of starting
     * another. `null` when there is none.
     */
    fun findPendingByText(text: DescriptionText): ApplicationStoreResult<PostingImport?>

    /**
     * A pending import with exactly this [sourceUrl] (already normalised by the caller), if any (#97, #187 finding
     * F6): a double submit of the same link. `null` when there is none.
     */
    fun findPendingBySourceUrl(sourceUrl: WebAddress): ApplicationStoreResult<PostingImport?>

    /**
     * Takes a lock on [key] (the normalised link, or the pasted text) that the caller's transaction holds until it
     * commits or rolls back, and that blocks any other transaction asking for the same key meanwhile (#187 finding
     * F6): a use case takes it before it looks for a pending import and keeps it through the fetch and the insert,
     * so a double submit waits, then finds the first import instead of fetching and importing a second time.
     * Different keys never wait for each other.
     */
    fun lockForStart(key: String): ApplicationStoreResult<Unit>
}
