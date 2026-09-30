// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.client.WireMock.post
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.anthropicStream
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.fixture
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * No prompt, answer or key reaches a log line (threat model T4), with every logger at TRACE except
 * the libraries `application.yaml` switches off (Spring AI logs prompts at WARN in some paths).
 */
class LogPrivacyTest {
    private val stub = ProviderStub()
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    /** Exactly the loggers `application.yaml` switches off. */
    private val silenced =
        listOf(
            "org.springframework.ai",
            "com.openai",
            "com.anthropic",
            "org.apache.hc.client5.http.headers",
            "org.apache.hc.client5.http.wire",
        )

    @BeforeEach
    fun capture() {
        root.level = Level.TRACE
        root.addAppender(appender)
        silenced.forEach { (LoggerFactory.getLogger(it) as Logger).level = Level.OFF }
    }

    @AfterEach
    fun release() {
        root.detachAppender(appender)
        root.level = Level.INFO
        stub.close()
    }

    @Test
    fun `successful and failing calls log neither content nor key`() {
        val adapter = stub.adapter()
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(okJson(fixture("openai/chat-completion.json"))),
        )
        adapter.complete(stub.target(ProviderKind.OPENAI), request()).javaClass shouldBe AiResult.Success::class.java
        stub.server.stubFor(
            post("/anthropic/v1/messages").willReturn(sse(anthropicStream("anthropic/message-stream.json"))),
        )
        adapter.stream(stub.target(ProviderKind.ANTHROPIC), request(), { false }) {}
        stub.server.stubFor(post("/mistral/v1/embeddings").willReturn(okJson(fixture("openai/embeddings.json"))))
        adapter.embed(stub.target(ProviderKind.MISTRAL), EmbeddingRequest(listOf(PROMPT_MARKER, "zweiter Text")))
        val refusal = okJson("""{"error":{"message":"bad $PROMPT_MARKER"}}""").withStatus(400)
        stub.server.stubFor(post("/openai/v1/chat/completions").willReturn(refusal))
        adapter.complete(stub.target(ProviderKind.OPENAI), request()) shouldBe AiResult.Rejected(400)

        appender.list.shouldNotBeEmpty()
        val logged = appLogs()
        logged shouldNotContain PROMPT_MARKER
        logged shouldNotContain ProviderStub.KEY
        logged shouldNotContain "Guten Tag"
    }

    /** Every captured line with its stack trace; WireMock plays the provider, so its own log is left out. */
    private fun appLogs(): String =
        appender.list
            .filterNot { it.loggerName.contains("wiremock", ignoreCase = true) }
            .joinToString("\n") { it.formattedMessage + it.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty() }

    private fun request() =
        LlmRequest(
            AiTask.CHAT,
            listOf(LlmMessage.System("Du bist Jofi."), LlmMessage.User("Mein Name ist $PROMPT_MARKER")),
        )

    private companion object {
        const val PROMPT_MARKER = "Erika-Mustermann-PRIVATE"
    }
}
