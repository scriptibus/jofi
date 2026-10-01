// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.lock

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InProcessKeyedLockAdapterTest {
    private val locks = InProcessKeyedLockAdapter()

    @Test
    fun `work for one key never overlaps`() {
        val running = AtomicInteger()
        val overlapped = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls =
                (1..8).map {
                    pool.submit {
                        locks.withLock("a", Duration.ofSeconds(10), { }) {
                            if (running.incrementAndGet() > 1) overlapped.incrementAndGet()
                            Thread.sleep(20)
                            running.decrementAndGet()
                        }
                    }
                }
            calls.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        overlapped.get() shouldBe 0
    }

    @Test
    fun `a caller that cannot get the key within its wait answers the timeout and runs nothing`() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            Thread {
                locks.withLock("k", Duration.ofSeconds(5), { }) {
                    held.countDown()
                    release.await()
                }
            }
        holder.start()
        held.await()

        val answer = locks.withLock("k", Duration.ofMillis(100), { "timed out" }) { "ran" }
        val other = locks.withLock("other", Duration.ofMillis(100), { "timed out" }) { "ran" }
        release.countDown()
        holder.join()

        answer shouldBe "timed out"
        other shouldBe "ran"
        locks.withLock("k", Duration.ofMillis(100), { "timed out" }) { "ran" } shouldBe "ran"
    }
}
