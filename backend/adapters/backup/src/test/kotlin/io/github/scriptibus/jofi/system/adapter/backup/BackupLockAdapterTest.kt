// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.backup

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uploads and restores run alone, exports together, and nothing ever waits. */
class BackupLockAdapterTest {
    private val lock = BackupLockAdapter()

    /** Holds [take] on another thread until the returned latch is released. */
    private fun held(take: (() -> String) -> String?): CountDownLatch {
        val taken = CountDownLatch(1)
        val release = CountDownLatch(1)
        CompletableFuture.runAsync {
            take {
                taken.countDown()
                release.await(WAIT, TimeUnit.SECONDS)
                "held"
            }
        }
        taken.await(WAIT, TimeUnit.SECONDS) shouldBe true
        return release
    }

    @Test
    fun `exclusive work shuts out everything else`() {
        val release = held { lock.exclusive(it) }

        lock.exclusive { "second" }.shouldBeNull()
        lock.shared { "export" }.shouldBeNull()

        release.countDown()
    }

    @Test
    fun `exports run together but shut out uploads and restores`() {
        val release = held { lock.shared(it) }

        lock.shared { "second export" } shouldBe "second export"
        lock.exclusive { "restore" }.shouldBeNull()

        release.countDown()
    }

    @Test
    fun `the lock is free again afterwards, also after a failure`() {
        runCatching { lock.exclusive { error("failed") } }

        lock.exclusive { "next" } shouldBe "next"
        lock.shared { "export" } shouldBe "export"
    }

    private companion object {
        const val WAIT = 10L
    }
}
