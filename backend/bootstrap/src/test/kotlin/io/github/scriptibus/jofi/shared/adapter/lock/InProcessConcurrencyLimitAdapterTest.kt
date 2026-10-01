// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.lock

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InProcessConcurrencyLimitAdapterTest {
    @Test
    fun `no more than the cap run at once, and the rest are turned away without waiting`() {
        val limit = InProcessConcurrencyLimitAdapter(2)
        val inside = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holders =
                (1..2).map {
                    pool.submit<String> {
                        limit.runIfFree({ "full" }) {
                            inside.countDown()
                            release.await()
                            "ran"
                        }
                    }
                }
            inside.await()

            limit.runIfFree({ "full" }) { "ran" } shouldBe "full"
            release.countDown()

            holders.map { it.get(10, TimeUnit.SECONDS) } shouldBe listOf("ran", "ran")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a permit is back after work that returns, answers a failure value or throws`() {
        val limit = InProcessConcurrencyLimitAdapter(1)

        limit.runIfFree({ "full" }) { "done" } shouldBe "done"
        limit.runIfFree({ "full" }) { "failure value" } shouldBe "failure value"
        shouldThrow<IllegalStateException> { limit.runIfFree({ "full" }) { error("boom") } }

        limit.runIfFree({ "full" }) { "free again" } shouldBe "free again"
    }

    @Test
    fun `a call turned away runs nothing and does not take a permit from the others`() {
        val limit = InProcessConcurrencyLimitAdapter(1)
        val ran = AtomicInteger()

        limit.runIfFree({ "full" }) {
            limit.runIfFree({ "full" }) { ran.incrementAndGet() } shouldBe "full"
            "outer"
        } shouldBe "outer"

        ran.get() shouldBe 0
        limit.runIfFree({ "full" }) { "free" } shouldBe "free"
    }

    @Test
    fun `under contention the cap holds and no permit leaks`() {
        val cap = 3
        val limit = InProcessConcurrencyLimitAdapter(cap)
        val running = AtomicInteger()
        val overCap = AtomicInteger()
        val threads = 16
        val start = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            (1..threads)
                .map {
                    pool.submit {
                        start.await()
                        repeat(50) {
                            limit.runIfFree({ }) {
                                if (running.incrementAndGet() > cap) overCap.incrementAndGet()
                                Thread.yield()
                                running.decrementAndGet()
                            }
                        }
                    }
                }.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        overCap.get() shouldBe 0
        holdsAll(limit, cap) shouldBe true
        holdsAll(limit, cap + 1) shouldBe false
    }

    /** Whether [count] permits can be held at the same time. */
    private fun holdsAll(
        limit: InProcessConcurrencyLimitAdapter,
        count: Int,
    ): Boolean = count == 0 || limit.runIfFree({ false }) { holdsAll(limit, count - 1) }

    @Test
    fun `a cap of zero or less is refused`() {
        listOf(0, -1).forEach {
            shouldThrow<IllegalArgumentException> { InProcessConcurrencyLimitAdapter(it) }
                .message shouldContain "must be positive"
        }
    }
}
