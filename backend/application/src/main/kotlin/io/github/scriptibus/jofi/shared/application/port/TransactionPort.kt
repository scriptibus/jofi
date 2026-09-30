// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.application.port

/**
 * One database transaction around several port calls, so a mutation and its changelog entry
 * (spec §13) are stored together or not at all. Ports return sealed results instead of throwing,
 * so the caller says which result may be committed.
 */
interface TransactionPort {
    /**
     * Runs [work] in one transaction and commits only when [commitIf] accepts its result; otherwise
     * everything [work] wrote is rolled back. Returns the result either way.
     */
    fun <T> inTransaction(
        commitIf: (T) -> Boolean,
        work: () -> T,
    ): T
}
