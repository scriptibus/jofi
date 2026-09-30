// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef

/**
 * The append-only audit trail every mutation writes to (spec §13 Auditability). Implementations
 * never throw: failures come back as [ChangelogResult.StorageFailure].
 */
interface ChangelogPort {
    /** Records [entry]. Entries are never updated or deleted afterwards. */
    fun append(entry: ChangelogEntry): ChangelogResult<Unit>

    /** The latest [limit] entries of [entity], returned oldest first (ties keep insertion order). */
    fun listByEntity(
        entity: EntityRef,
        limit: ChangelogLimit,
    ): ChangelogResult<List<ChangelogEntry>>

    /** The latest entries across all entities, newest first. */
    fun listRecent(limit: ChangelogLimit): ChangelogResult<List<ChangelogEntry>>
}
