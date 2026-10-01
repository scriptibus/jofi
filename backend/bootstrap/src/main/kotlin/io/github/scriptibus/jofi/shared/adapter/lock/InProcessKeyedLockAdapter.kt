// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.lock

import io.github.scriptibus.jofi.shared.application.port.KeyedLockPort
import org.springframework.stereotype.Component
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
        work: () -> T,
    ): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        entry.lock.lock()
        try {
            return work()
        } finally {
            entry.lock.unlock()
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
