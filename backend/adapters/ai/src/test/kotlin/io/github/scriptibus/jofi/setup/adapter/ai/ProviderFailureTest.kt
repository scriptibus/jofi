// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.post
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.adapter.net.Destination
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Provider errors become sealed results, streams stop on cancellation, and requests only reach
 * destinations the SSRF guard allows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderFailureTest {
    private val stub = ProviderStub()
    private val adapter = stub.adapter()
    private val chatPath = "/openai/v1/chat/completions"

    @BeforeEach
    fun reset() {
        stub.server.resetAll()
    }

    @AfterAll
    fun stop() {
        stub.close()
    }

    @Test
    fun `a rejected key is an authentication failure`() {
        stub.server.stubFor(
            post(chatPath).willReturn(okJson(ProviderStub.fixture("openai/error-auth.json")).withStatus(401)),
        )

        complete() shouldBe AiResult.AuthenticationFailed
    }

    @Test
    fun `throttling is rate limited with the provider's retry delay`() {
        stub.server.stubFor(
            post(
                chatPath,
            ).willReturn(
                okJson("""{"error":{"message":"Rate limit reached"}}""").withStatus(429).withHeader("Retry-After", "7"),
            ),
        )

        complete() shouldBe AiResult.RateLimited(Duration.ofSeconds(7))
    }

    @Test
    fun `an overlong prompt is context too long`() {
        stub.server.stubFor(
            post(chatPath).willReturn(okJson(ProviderStub.fixture("openai/error-context-length.json")).withStatus(400)),
        )

        complete() shouldBe AiResult.ContextTooLong
    }

    @Test
    fun `a model without tool support is a missing capability`() {
        stub.server.stubFor(
            post(chatPath).willReturn(okJson(ProviderStub.fixture("openai/error-no-tools.json")).withStatus(400)),
        )

        val request =
            LlmRequest(
                AiTask.CHAT,
                listOf(LlmMessage.User("Hi")),
                listOf(ToolDefinition("t", "d", """{"type":"object"}""")),
            )

        adapter.complete(stub.target(ProviderKind.OPENAI), request) shouldBe AiResult.CapabilityMissing(AiTask.CHAT)
    }

    @Test
    fun `a server error is unavailable and any other refusal is rejected with its status`() {
        stub.server.stubFor(post(chatPath).willReturn(okJson("""{"error":{"message":"boom"}}""").withStatus(503)))
        complete() shouldBe AiResult.Unavailable

        stub.server.stubFor(
            post(chatPath).willReturn(okJson("""{"error":{"message":"no such model"}}""").withStatus(404)),
        )
        complete() shouldBe AiResult.Rejected(404)
    }

    @Test
    fun `a missing key fails before any request`() {
        val target = stub.target(ProviderKind.OPENAI)
        val unknownKey = ResolvedModel(target.provider.copy(apiKey = SecretId(UUID.randomUUID())), target.model)

        adapter.complete(unknownKey, request()) shouldBe AiResult.AuthenticationFailed
        stub.server.verify(0, anyRequestedFor(anyUrl()))
    }

    @Test
    fun `cancelling a stream stops it at once`() {
        stub.server.stubFor(
            post(
                chatPath,
            ).willReturn(sse(ProviderStub.openAiStream("openai/chat-stream.json")).withChunkedDribbleDelay(20, 20_000)),
        )
        var fragments = 0
        val started = System.nanoTime()

        val result = adapter.stream(stub.target(ProviderKind.OPENAI), request(), { fragments > 0 }) { fragments++ }

        result shouldBe AiResult.Cancelled
        // The whole stream takes 20 s; stopping after the first fragment must not wait for the rest.
        (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(12)) shouldBe true
    }

    @Test
    fun `a failing fragment consumer cancels the stream`() {
        stub.server.stubFor(post(chatPath).willReturn(sse(ProviderStub.openAiStream("openai/chat-stream.json"))))

        adapter.stream(
            stub.target(ProviderKind.OPENAI),
            request(),
            { false },
        ) { error("the chat socket closed") } shouldBe
            AiResult.Cancelled
    }

    @Test
    fun `a provider on an internal address that is not allowlisted is blocked`() {
        val closed = stub.adapter(GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test"))
        stub.server.stubFor(post(chatPath).willReturn(okJson(ProviderStub.fixture("openai/chat-completion.json"))))

        closed.complete(stub.target(ProviderKind.OPENAI), request()) shouldBe AiResult.Unavailable
        stub.server.verify(0, anyRequestedFor(anyUrl()))
    }

    @Test
    fun `a local endpoint on the metadata address stays blocked even when it is configured`() {
        val metadata = URI("http://169.254.169.254/v1")
        val allowlisted =
            GuardedAiTransport.create(
                DestinationAllowlist.of(listOfNotNull(Destination.of(metadata))),
                "Jofi/test",
            )
        val provider =
            ProviderConfig(ProviderId(UUID.randomUUID()), "metadata", ProviderKind.OPENAI_COMPATIBLE, null, metadata)

        val result = stub.adapter(allowlisted).complete(ResolvedModel(provider, ModelName("llama3.1")), request())

        result shouldBe AiResult.Unavailable
    }

    @Test
    fun `Anthropic has no embeddings and is not called for them`() {
        val result =
            adapter.embed(
                stub.target(ProviderKind.ANTHROPIC),
                io.github.scriptibus.jofi.shared.domain.ai
                    .EmbeddingRequest(listOf("x")),
            )

        result.shouldBeInstanceOf<AiResult.CapabilityMissing>().task shouldBe AiTask.EMBEDDING
        stub.server.verify(0, anyRequestedFor(anyUrl()))
    }

    private fun request() = LlmRequest(AiTask.CHAT, listOf(LlmMessage.User("Hallo")))

    private fun complete() = adapter.complete(stub.target(ProviderKind.OPENAI), request())
}
