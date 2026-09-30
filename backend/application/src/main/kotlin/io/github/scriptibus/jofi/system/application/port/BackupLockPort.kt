// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application.port

/**
 * One lock for all backup work (ADR-0042): uploads and restores run alone, exports may run together.
 * Nothing waits: a call that would have to returns `null`, and the caller answers "busy".
 * Implementations never throw.
 */
interface BackupLockPort {
    /** Runs [work] with nothing else backup-related running, or returns `null` at once. */
    fun <T : Any> exclusive(work: () -> T): T?

    /** Runs [work] next to other shared work (exports), or returns `null` at once. */
    fun <T : Any> shared(work: () -> T): T?
}
