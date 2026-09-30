// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.persistence

import io.github.scriptibus.jofi.setup.domain.SetupStoreResult
import org.jooq.exception.DataAccessException
import org.slf4j.Logger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Runs one store call of a `setup` repository. No exception crosses the port (AGENTS.md §3): only the
 * operation and the exception type are logged, since exception messages can carry row values.
 */
internal fun <T> storeCall(
    log: Logger,
    operation: String,
    block: () -> SetupStoreResult<T>,
): SetupStoreResult<T> =
    try {
        block()
    } catch (exception: RuntimeException) {
        log.error("Setup store {} failed: {}", operation, exception.javaClass.name)
        SetupStoreResult.StorageFailure(operation)
    }

/** PostgreSQL's foreign key violation: the row is still referenced. */
internal fun DataAccessException.isStillReferenced(): Boolean = sqlState() == "23503"

internal fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun <T> T?.foundOrNotFound(): SetupStoreResult<T> =
    if (this == null) SetupStoreResult.NotFound else SetupStoreResult.Success(this)
