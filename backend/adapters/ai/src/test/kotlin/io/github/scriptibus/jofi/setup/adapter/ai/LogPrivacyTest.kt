// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.http.Fault
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.okJson
import io.github.scriptibus.jofi.setup.adapter.ai.OpenAiFamilyAdapterTest.Companion.sse
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.anthropicStream
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.fixture
import io.github.scriptibus.jofi.setup.adapter.ai.ProviderStub.Companion.openAiStream
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.EmbeddingRequest
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.shared.domain.ai.ToolCall
import io.github.scriptibus.jofi.shared.domain.ai.ToolDefinition
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * No prompt, tool argument, answer or key reaches a log line (threat model T4). Every logger runs at
 * TRACE except those the app's real `application.yaml` sets (Spring AI logs prompts even at WARN in
 * some paths); success, streaming, tools, cancellation, blocked destinations, I/O failures,
 * provider errors and model listing all run with the marker in their content.
 */
class LogPrivacyTest {
    private val stub = ProviderStub()
    private val adapter = stub.adapter()
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val appLevels = AppLogLevels.load()
    private val previousLevels = mutableMapOf<Logger, Level?>()

    @BeforeEach
    fun capture() {
        setLevel(ROOT, Level.TRACE)
        appLevels.forEach { (name, level) -> setLevel(name, Level.toLevel(level)) }
        logger(ROOT).addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger(ROOT).detachAppender(appender)
        previousLevels.forEach { (logger, level) -> logger.level = level }
        stub.close()
    }

    @Test
    fun `the app switches off the libraries that log content`() {
        listOf("org.springframework.ai", "com.openai", "com.anthropic").forEach { appLevels[it] shouldBe "off" }
    }

    @Test
    fun `completions, streams and embeddings`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(okJson(fixture("openai/chat-completion.json"))),
        )
        adapter.complete(stub.target(ProviderKind.OPENAI), request()).javaClass shouldBe AiResult.Success::class.java
        stub.server.stubFor(
            post("/gemini/v1beta/openai/chat/completions").willReturn(sse(openAiStream("openai/chat-stream.json"))),
        )
        adapter.stream(stub.target(ProviderKind.GEMINI), request(), { false }, {}) {}
        stub.server.stubFor(
            post("/anthropic/v1/messages").willReturn(sse(anthropicStream("anthropic/message-stream.json"))),
        )
        adapter.stream(stub.target(ProviderKind.ANTHROPIC), request(), { false }, {}) {}
        stub.server.stubFor(post("/mistral/v1/embeddings").willReturn(okJson(fixture("openai/embeddings.json"))))
        adapter.embed(stub.target(ProviderKind.MISTRAL), EmbeddingRequest.ofTexts(listOf(MARKER, "zweiter Text")))

        assertNothingLeaked()
    }

    @Test
    fun `tool calls and their arguments`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(okJson(fixture("openai/chat-tool-call.json"))),
        )
        stub.server.stubFor(
            post("/anthropic/v1/messages").willReturn(okJson(fixture("anthropic/message-tool-use.json"))),
        )

        adapter.complete(stub.target(ProviderKind.OPENAI), toolRequest()).javaClass shouldBe
            AiResult.Success::class.java
        adapter.complete(stub.target(ProviderKind.ANTHROPIC), toolRequest()).javaClass shouldBe
            AiResult.Success::class.java

        assertNothingLeaked()
    }

    @Test
    fun `cancelled streams, blocked destinations and broken connections`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(sse(openAiStream("openai/chat-stream.json"))),
        )
        adapter.stream(stub.target(ProviderKind.OPENAI), request(), { false }, {}) { error(MARKER) } shouldBe
            AiResult.Cancelled
        val blocked = stub.adapter(GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test"))
        blocked.complete(stub.target(ProviderKind.OPENAI), request()) shouldBe AiResult.Unavailable
        stub.server.stubFor(
            post("/mistral/v1/chat/completions").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )
        adapter.complete(stub.target(ProviderKind.MISTRAL), request()) shouldBe AiResult.Unavailable

        assertNothingLeaked()
    }

    @Test
    fun `provider errors that quote the prompt, and model listing`() {
        val quoting = """{"type":"error","error":{"type":"invalid_request_error","message":"bad input: $MARKER"}}"""
        stub.server.stubFor(post("/anthropic/v1/messages").willReturn(okJson(quoting).withStatus(400)))
        adapter.complete(stub.target(ProviderKind.ANTHROPIC), request()) shouldBe AiResult.Rejected(400)
        stub.server.stubFor(
            post(
                "/openai/v1/chat/completions",
            ).willReturn(okJson("""{"error":{"message":"$MARKER"}}""").withStatus(500)),
        )
        adapter.complete(stub.target(ProviderKind.OPENAI), request()) shouldBe AiResult.Unavailable
        stub.server.stubFor(get("/openai/v1/models").willReturn(okJson(fixture("openai/models.json"))))
        stub.catalog().detect(stub.provider(ProviderKind.OPENAI)).javaClass shouldBe AiResult.Success::class.java
        stub.server.stubFor(get(urlPathEqualTo("/anthropic/v1/models")).willReturn(okJson(quoting).withStatus(401)))
        stub.catalog().detect(stub.provider(ProviderKind.ANTHROPIC)) shouldBe AiResult.AuthenticationFailed

        assertNothingLeaked()
    }

    private fun assertNothingLeaked() {
        appender.list.shouldNotBeEmpty()
        val logged = appLogs()
        logged shouldNotContain MARKER
        logged shouldNotContain ProviderStub.KEY
        logged shouldNotContain "Guten Tag"
        logged shouldNotContain "ACME GmbH"
    }

    @Test
    fun `the gateway with its filter, budget and meter`() {
        stub.server.stubFor(
            post("/openai/v1/chat/completions").willReturn(okJson(fixture("openai/chat-completion.json"))),
        )
        val setup = InMemorySetup()
        val target = stub.target(ProviderKind.OPENAI, "gpt-4o-mini")
        setup.providers[target.provider.id] = target.provider
        listOf(AiTask.CHAT, AiTask.SCANNER_PRE_SCORING).forEach {
            setup.assignments[it] = ModelAssignment(it, target.provider.id, target.model)
        }
        val visibility = FakeVisibility(NeverSendRules(emptyMap(), setOf(FlaggedValue(MARKER))))
        val gateway =
            AiGatewayAdapter(
                adapter,
                AiRouter(setup.assignmentPort, setup.providerPort, setup.capabilityPort, stub.catalog()),
                NeverSendGuard(visibility),
                AiMeter(setup.costPort, setup.budgetPort, PriceTableFile.load(), ProviderStub.CLOCK),
            )

        gateway.complete(request()).shouldBeInstanceOf<AiResult.Success<*>>()
        setup.budget = MonthlyBudget(Money.usd(1))
        gateway.complete(request().copy(task = AiTask.SCANNER_PRE_SCORING)) shouldBe
            AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)
        visibility.throwing = true
        gateway.complete(request()) shouldBe AiResult.PrivacyFilterFailed(AiTask.CHAT)
        setup.failing = true
        gateway.complete(request()) shouldBe AiResult.Unavailable

        assertNothingLeaked()
    }

    /** Every captured line with its stack trace; WireMock plays the provider, so its own log is left out. */
    private fun appLogs(): String =
        appender.list
            .filterNot { it.loggerName.contains("wiremock", ignoreCase = true) }
            .joinToString("\n") { it.formattedMessage + it.throwableProxy?.let(ThrowableProxyUtil::asString).orEmpty() }

    private fun setLevel(
        name: String,
        level: Level,
    ) {
        val logger = logger(name)
        previousLevels.putIfAbsent(logger, logger.level)
        logger.level = level
    }

    private fun logger(name: String): Logger = LoggerFactory.getLogger(name) as Logger

    private fun request() =
        LlmRequest(AiTask.CHAT, listOf(LlmMessage.System("Du bist Jofi."), LlmMessage.User("Mein Name ist $MARKER")))

    private fun toolRequest() =
        LlmRequest(
            AiTask.CHAT,
            listOf(
                LlmMessage.User("Suche $MARKER"),
                LlmMessage.Assistant("", listOf(ToolCall("call_1", "find_person", """{"name":"$MARKER"}"""))),
                LlmMessage.ToolResult("call_1", "Gefunden: $MARKER"),
            ),
            tools = listOf(ToolDefinition("find_company", "Finds a company", """{"type":"object"}""")),
        )

    private companion object {
        const val ROOT = org.slf4j.Logger.ROOT_LOGGER_NAME
        const val MARKER = "Erika-Mustermann-PRIVATE"
    }
}
