// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import org.postgresql.util.PSQLException

/**
 * The name of the constraint PostgreSQL reported as violated anywhere in the cause chain, or `null`.
 * The chain is searched because the wrapper differs: plain jOOQ throws its `DataAccessException`,
 * while the app's `DSLContext` translates into Spring's `DataIntegrityViolationException`.
 * Repositories of every context map violations by this name (ADR-0041).
 */
internal fun Throwable.violatedConstraint(): String? =
    generateSequence(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .firstNotNullOfOrNull { it.serverErrorMessage?.constraint }
