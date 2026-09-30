// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.anthropicEvents
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.fixture
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.openAiEvents
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancelled streams really end: the provider sees the client hang up (checked on a raw socket, as
 * WireMock cannot show it), even when the provider stalls, and no pooled connection is left behind.
 */
class StreamCancellationTest {
    private val stub = ProviderStub()
    private val providers = mutableListOf<StallingProvider>()

    @AfterEach
    fun stop() {
        providers.forEach(StallingProvider::close)
        stub.close()
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "ANTHROPIC"])
    fun `cancelling after the first fragment hangs up on a provider that then stalls`(kind: ProviderKind) {
        val provider = stalling(firstFragment(kind))
        var fragments = 0

        val result =
            stub
                .adapterOn(
                    provider.port,
                ).stream(stub.target(kind), request(), { fragments > 0 }, {}) { fragments++ }

        result shouldBe AiResult.Cancelled
        awaitHangUps(provider, expected = 1)
    }

    @ParameterizedTest
    @EnumSource(names = ["OPENAI", "ANTHROPIC"])
    fun `a failing fragment consumer hangs up too`(kind: ProviderKind) {
        val provider = stalling(firstFragment(kind))

        val result =
            stub
                .adapterOn(
                    provider.port,
                ).stream(stub.target(kind), request(), { false }, {}) { error("socket closed") }

        result shouldBe AiResult.Cancelled
        awaitHangUps(provider, expected = 1)
    }

    @Test
    fun `an Anthropic stream cancelled before its headers starts no request`() {
        val provider = stalling(firstFragment(ProviderKind.ANTHROPIC), headerDelay = Duration.ofSeconds(2))
        val adapter = stub.adapterOn(provider.port)

        repeat(6) {
            adapter.stream(stub.target(ProviderKind.ANTHROPIC), request(), { true }, {}) {} shouldBe
                AiResult.Cancelled
        }

        provider.requests.get() shouldBe 0
        provider.answerAnd { adapter.complete(stub.target(ProviderKind.ANTHROPIC), request()) }
    }

    @Test
    fun `an Anthropic stream cancelled while waiting for headers releases its connection`() {
        val provider = stalling(firstFragment(ProviderKind.ANTHROPIC), headerDelay = Duration.ofSeconds(1))
        val adapter = stub.adapterOn(provider.port)

        repeat(6) {
            val polls = AtomicInteger()
            // Not cancelled when the call starts, then cancelled while the headers are still pending.
            adapter.stream(
                stub.target(ProviderKind.ANTHROPIC),
                request(),
                { polls.incrementAndGet() > 1 },
                {},
            ) {} shouldBe
                AiResult.Cancelled
        }

        // A request cancelled before it connected never reaches the provider; every connection that
        // did (some land just after the call returned) must be hung up once the delayed headers were due.
        awaitAllHungUp(provider)
        provider.answerAnd { adapter.complete(stub.target(ProviderKind.ANTHROPIC), request()) }
    }

    /** Switches [this] provider to answering and checks that [call] succeeds promptly. */
    private fun StallingProvider.answerAnd(call: () -> AiResult<*>) {
        answer = fixture("anthropic/message.json")
        val started = System.nanoTime()

        call().shouldBeInstanceOf<AiResult.Success<*>>()

        (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(3)) shouldBe true
    }

    private fun awaitHangUps(
        provider: StallingProvider,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (provider.hangUps.get() < expected && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        provider.hangUps.get() shouldBe expected
    }

    /** Waits until the provider saw as many hang-ups as connections, reading both live. */
    private fun awaitAllHungUp(provider: StallingProvider) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (provider.hangUps.get() != provider.requests.get() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        "${provider.hangUps.get()} hang-ups for ${provider.requests.get()} connections" shouldBe
            "${provider.requests.get()} hang-ups for ${provider.requests.get()} connections"
    }

    private fun stalling(
        events: String,
        headerDelay: Duration = Duration.ZERO,
    ): StallingProvider = StallingProvider(events, headerDelay).also(providers::add)

    private fun firstFragment(kind: ProviderKind): String =
        if (kind == ProviderKind.ANTHROPIC) {
            anthropicEvents("anthropic/message-stream.json", ANTHROPIC_EVENTS_TO_FIRST_TEXT)
        } else {
            openAiEvents("openai/chat-stream.json", OPENAI_CHUNKS_TO_FIRST_TEXT)
        }

    private fun request() = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo")))

    private companion object {
        const val OPENAI_CHUNKS_TO_FIRST_TEXT = 2
        const val ANTHROPIC_EVENTS_TO_FIRST_TEXT = 4
        const val POLL_MILLIS = 50L
    }
}
