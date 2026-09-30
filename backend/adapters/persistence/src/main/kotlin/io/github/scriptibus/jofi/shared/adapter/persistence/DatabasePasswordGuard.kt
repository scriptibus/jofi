// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.stereotype.Component

/**
 * Stops startup when the database password is missing, instead of silently connecting with an empty
 * one. `application.yaml` takes it from `JOFI_DB_PASSWORD` without a default; a service connection
 * (Testcontainers in tests and `bootTestRun`) supplies its own password and passes as well.
 */
@Component
class DatabasePasswordGuard(
    connectionDetails: JdbcConnectionDetails,
) {
    init {
        val password = connectionDetails.password
        // An unresolved `${JOFI_DB_PASSWORD}` placeholder can reach this point as literal text.
        check(!password.isNullOrBlank() && !password.startsWith("\${")) {
            "JOFI_DB_PASSWORD is not set: the database password must come from the environment"
        }
    }
}
