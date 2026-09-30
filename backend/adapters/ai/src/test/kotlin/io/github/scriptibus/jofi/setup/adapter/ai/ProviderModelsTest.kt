// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.WireMock.post
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.net.AnthropicSdkHttpClient
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.adapter.net.OpenAiSdkHttpClient
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.management.ManagementFactory
import java.util.concurrent.ExecutorService

/** The SDK clients built per call leave nothing behind: no threads, no stopped shared executor. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderModelsTest {
    private val stub = ProviderStub()
    private val adapter = stub.adapter()

    @AfterAll
    fun stop() {
        stub.close()
    }

    @Test
    fun `many calls do not grow the thread count`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(okJson(ProviderStub.fixture("openai/chat-completion.json"))),
        )
        stub.server.stubFor(
            post("/anthropic/v1/messages").willReturn(okJson(ProviderStub.fixture("anthropic/message.json"))),
        )
        repeat(WARM_UP) { callBoth() }
        val threads = ManagementFactory.getThreadMXBean()
        val before = threads.threadCount

        repeat(CALLS) { callBoth() }

        // A DefaultSleeper per client would add a Timer thread per call (two per round here).
        (threads.threadCount - before <= TOLERATED_GROWTH) shouldBe true
    }

    @Test
    fun `the SDKs cannot shut down the shared stream executor`() {
        val models = stub.models()
        val provider = stub.provider(ProviderKind.OPENAI)
        val transport = GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test")

        // An ExecutorService would be wrapped and shut down when any client is closed or collected.
        (
            models
                .openAiOptions(
                    provider,
                    null,
                    OpenAiSdkHttpClient(transport),
                ).streamHandlerExecutor is ExecutorService
        ) shouldBe
            false
        val anthropic = stub.provider(ProviderKind.ANTHROPIC)
        (
            models
                .anthropicOptions(
                    anthropic,
                    null,
                    AnthropicSdkHttpClient(transport),
                ).streamHandlerExecutor is ExecutorService
        ) shouldBe
            false
    }

    @Test
    fun `streaming still works after clients were closed and collected`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(sse(ProviderStub.openAiStream("openai/chat-stream.json"))),
        )
        repeat(WARM_UP) { adapter.stream(stub.target(ProviderKind.OPENAI), request(), { false }, {}) {} }
        repeat(GC_ROUNDS) { System.gc() }

        adapter
            .stream(
                stub.target(ProviderKind.OPENAI),
                request(),
                { false },
                {},
            ) {}
            .shouldBeInstanceOf<AiResult.Success<*>>()
    }

    private fun callBoth() {
        adapter.complete(stub.target(ProviderKind.OPENAI), request()).shouldBeInstanceOf<AiResult.Success<*>>()
        adapter.complete(stub.target(ProviderKind.ANTHROPIC), request()).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    private fun request() = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo")))

    private companion object {
        const val WARM_UP = 3
        const val CALLS = 20
        const val TOLERATED_GROWTH = 4
        const val GC_ROUNDS = 5
    }
}
