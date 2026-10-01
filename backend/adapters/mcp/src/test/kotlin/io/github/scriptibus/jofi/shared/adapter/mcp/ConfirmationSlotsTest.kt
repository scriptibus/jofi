// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.mcp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfirmationSlotsTest {
    @Test
    fun `a session holds one slot and the server only as many as its limit`() {
        val slots = ConfirmationSlots(2)

        val first = slots.tryReserve("a").shouldNotBeNull()
        slots.tryReserve("a").shouldBeNull()
        slots.tryReserve("b").shouldNotBeNull()
        slots.tryReserve("c").shouldBeNull()

        first.close()
        slots.tryReserve("c").shouldNotBeNull().close()
        slots.tryReserve("a").shouldNotBeNull()
    }

    @Test
    fun `of many concurrent calls of one session exactly one gets the slot`() {
        val slots = ConfirmationSlots(4)
        val pool = Executors.newFixedThreadPool(THREADS)
        val start = CountDownLatch(1)
        try {
            val granted =
                (1..THREADS)
                    .map {
                        pool.submit<Boolean> {
                            start.await()
                            slots.tryReserve("same-session") != null
                        }
                    }.also { start.countDown() }
                    .count { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            granted shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `at least one slot is required`() {
        shouldThrow<IllegalArgumentException> { ConfirmationSlots(0) }
    }

    private companion object {
        const val THREADS = 16
        const val TIMEOUT_SECONDS = 10L
    }
}
