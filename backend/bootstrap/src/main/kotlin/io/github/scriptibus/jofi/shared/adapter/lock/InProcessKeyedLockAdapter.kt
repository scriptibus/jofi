// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.lock

import io.github.scriptibus.jofi.shared.application.port.KeyedLockPort
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * One [ReentrantLock] per key in use, removed when its last user leaves, so the map never grows. Waiting costs a
 * (virtual) thread, no database connection. Memory only: a restart forgets nothing that matters, since nothing is
 * stored while a key is locked.
 */
@Component
class InProcessKeyedLockAdapter : KeyedLockPort {
    private class Entry {
        val lock = ReentrantLock()
        var users = 0
    }

    private val entries = HashMap<String, Entry>()

    override fun <T> withLock(
        key: String,
        wait: Duration,
        onTimeout: () -> T,
        work: () -> T,
    ): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            if (!acquired(entry.lock, wait)) return onTimeout()
            try {
                return work()
            } finally {
                entry.lock.unlock()
            }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }

    /** Waiting for the lock is interruptible; an interrupted caller counts as timed out and keeps its flag. */
    private fun acquired(
        lock: ReentrantLock,
        wait: Duration,
    ): Boolean =
        try {
            lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
