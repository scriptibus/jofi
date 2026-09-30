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

        repeat(CALLS) { call ->
            val polls = AtomicInteger()
            // Not cancelled when the call starts; cancelled once the provider holds the request and
            // is still delaying its headers.
            adapter.stream(
                stub.target(ProviderKind.ANTHROPIC),
                request(),
                { (polls.incrementAndGet() > 1).also { if (it) provider.awaitReceived(call + 1) } },
                {},
            ) {} shouldBe
                AiResult.Cancelled
        }

        // Every call reached the provider before it was cancelled, and each connection was hung up.
        provider.received.get() shouldBe CALLS
        awaitHangUps(provider, expected = CALLS)
        provider.requests.get() shouldBe CALLS
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

    /** Waits (up to 5 s) until the provider has read [count] requests in full; the test checks it did. */
    private fun StallingProvider.awaitReceived(count: Int) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (received.get() < count && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
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
        const val POLL_MILLIS = 10L
        const val CALLS = 6
    }
}
