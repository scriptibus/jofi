// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.lock

import io.github.scriptibus.jofi.shared.application.port.ConcurrencyLimitPort
import java.util.concurrent.Semaphore

/**
 * A [Semaphore] of [maxConcurrent] permits that is never waited on: [Semaphore.tryAcquire] either gets a permit now
 * or the call is turned away. Memory only, since nothing is stored while a call runs. Not a component: each limit
 * is a bean of its own, sized by its configuration (`PostingImportConfiguration`).
 */
class InProcessConcurrencyLimitAdapter(
    maxConcurrent: Int,
) : ConcurrencyLimitPort {
    private val permits: Semaphore

    init {
        require(maxConcurrent > 0) { "A concurrency limit must be positive, but is $maxConcurrent" }
        permits = Semaphore(maxConcurrent)
    }

    override fun <T> runIfFree(
        onFull: () -> T,
        work: () -> T,
    ): T {
        if (!permits.tryAcquire()) return onFull()
        try {
            return work()
        } finally {
            permits.release()
        }
    }
}
