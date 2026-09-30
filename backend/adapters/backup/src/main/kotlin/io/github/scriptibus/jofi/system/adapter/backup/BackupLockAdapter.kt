// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.github.scriptibus.jofi.system.application.port.BackupLockPort
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The backup lock in memory (ADR-0042): only `app` serves backup requests and restore recovery runs
 * in it before the port opens, so one process-wide read/write lock is enough. `tryLock` never waits.
 */
@Component
class BackupLockAdapter : BackupLockPort {
    private val lock = ReentrantReadWriteLock()

    override fun <T : Any> exclusive(work: () -> T): T? {
        val exclusive = lock.writeLock()
        if (!exclusive.tryLock()) return null
        return try {
            work()
        } finally {
            exclusive.unlock()
        }
    }

    override fun <T : Any> shared(work: () -> T): T? {
        val shared = lock.readLock()
        if (!shared.tryLock()) return null
        return try {
            work()
        } finally {
            shared.unlock()
        }
    }
}
