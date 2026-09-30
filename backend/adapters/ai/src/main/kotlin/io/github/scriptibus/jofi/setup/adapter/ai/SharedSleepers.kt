// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.anthropic.core.Sleeper as AnthropicSleeper
import com.openai.core.Sleeper as OpenAiSleeper

/**
 * One sleeper per SDK for every client. Without it each `ClientOptions.build()` creates a
 * `DefaultSleeper`, which starts its own `Timer` thread, so every AI call would leave a thread
 * behind until the client is collected. Retries are off (`maxRetries(0)`), so the sleepers are
 * practically never used; they rely on the JDK's shared delayed executor and own no thread.
 * `close()` does nothing, since the SDK closes a client's sleeper when the client is collected.
 */
internal object SharedSleepers {
    val openAi: OpenAiSleeper =
        object : OpenAiSleeper {
            override fun sleep(duration: Duration) = Thread.sleep(duration)

            override fun sleepAsync(duration: Duration): CompletableFuture<Void> = delay(duration)

            override fun close() = Unit
        }

    val anthropic: AnthropicSleeper =
        object : AnthropicSleeper {
            override fun sleep(duration: Duration) = Thread.sleep(duration)

            override fun sleepAsync(duration: Duration): CompletableFuture<Void> = delay(duration)

            override fun close() = Unit
        }

    private fun delay(duration: Duration): CompletableFuture<Void> =
        CompletableFuture.runAsync({}, CompletableFuture.delayedExecutor(duration.toNanos(), TimeUnit.NANOSECONDS))
}
