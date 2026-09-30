// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.persistence

import io.github.scriptibus.jofi.shared.application.port.TransactionPort
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Spring's JDBC transaction around use-case work. jOOQ joins it (Spring Boot's jOOQ auto-configuration
 * uses the transaction-aware data source), so every repository call in [inTransaction] shares it.
 */
@Component
class TransactionAdapter(
    transactionManager: PlatformTransactionManager,
) : TransactionPort {
    private val template = TransactionTemplate(transactionManager)

    override fun <T> inTransaction(
        commitIf: (T) -> Boolean,
        work: () -> T,
    ): T {
        var result: Result<T>? = null
        template.executeWithoutResult { status ->
            val outcome = work()
            if (!commitIf(outcome)) status.setRollbackOnly()
            result = Result.success(outcome)
        }
        return checkNotNull(result) { "The transaction callback did not run" }.getOrThrow()
    }
}
