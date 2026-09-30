// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.anthropic.errors.AnthropicIoException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.openai.core.http.HttpMethod
import com.openai.core.http.HttpRequest
import com.openai.errors.OpenAIIoException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import com.anthropic.core.http.HttpMethod as AnthropicHttpMethod
import com.anthropic.core.http.HttpRequest as AnthropicHttpRequest

/**
 * The AI transport (ADR-0034, ADR-0039): the SSRF guard with the allowlist of configured AI
 * endpoints, no redirects, no SDK telemetry headers, and a close that aborts instead of draining.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardedAiTransportTest {
    private val provider = WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
    private val base = "http://127.0.0.1:${provider.port()}"
    private val allowlisted = DestinationAllowlist.of(listOf(Destination.of("127.0.0.1", provider.port())))
    private val transport = GuardedAiTransport.create(allowlisted, "Jofi/test")

    @BeforeEach
    fun reset() {
        provider.resetAll()
    }

    @AfterAll
    fun stop() {
        transport.close()
        provider.stop()
    }

    @Test
    fun `posts to an allowlisted local endpoint with Jofi's user agent and without SDK telemetry`() {
        provider.stubFor(post("/v1/chat/completions").willReturn(aResponse().withBody("""{"ok":true}""")))
        val body = AiRequestBody("application/json", 2, true) { it.write("{}".toByteArray()) }
        val headers = listOf("User-Agent" to "OpenAI/Java", "X-Stainless-OS" to "Linux", "Authorization" to "Bearer k")

        val status =
            transport
                .execute(
                    AiRequest("POST", URI("$base/v1/chat/completions"), headers, body),
                ).use { it.statusCode }

        status shouldBe 200
        provider.verify(
            postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("User-Agent", equalTo("Jofi/test"))
                .withHeader("Authorization", equalTo("Bearer k"))
                .withHeader("X-Stainless-OS", absent())
                .withHeader("Content-Type", equalTo("application/json")),
        )
    }

    @Test
    fun `refuses a local endpoint that is not allowlisted`() {
        val closed = GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test")

        shouldThrow<BlockedDestinationException> {
            closed.execute(AiRequest("GET", URI("$base/v1/models"), emptyList(), null))
        }.addressClass shouldBe AddressClass.LOOPBACK
        provider.verify(0, anyRequestedFor(anyUrl()))
    }

    @Test
    fun `keeps the metadata endpoint blocked even when it is allowlisted`() {
        val metadata = DestinationAllowlist.of(listOf(Destination.of("169.254.169.254", 80)))
        val guarded = GuardedAiTransport.create(metadata, "Jofi/test")

        shouldThrow<BlockedDestinationException> {
            guarded.execute(AiRequest("GET", URI("http://169.254.169.254/latest/meta-data"), emptyList(), null))
        }.addressClass shouldBe AddressClass.LINK_LOCAL
    }

    @Test
    fun `does not follow redirects`() {
        provider.stubFor(
            get("/v1/models").willReturn(aResponse().withStatus(302).withHeader("Location", "$base/moved")),
        )

        val status =
            transport
                .execute(
                    AiRequest("GET", URI("$base/v1/models"), emptyList(), null),
                ).use { it.statusCode }

        status shouldBe 302
        provider.verify(0, getRequestedFor(urlEqualTo("/moved")))
    }

    @Test
    fun `closing an unfinished stream aborts it instead of reading it to the end`() {
        provider.stubFor(
            get("/v1/stream").willReturn(aResponse().withBody("x".repeat(10_000)).withChunkedDribbleDelay(100, 10_000)),
        )
        val response = transport.execute(AiRequest("GET", URI("$base/v1/stream"), emptyList(), null))
        response.body.read()

        val started = System.nanoTime()
        response.close()

        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(2)
    }

    @Test
    fun `the request deadline cuts off a response that trickles on`() {
        provider.stubFor(
            get("/v1/slow").willReturn(aResponse().withBody("x".repeat(10_000)).withChunkedDribbleDelay(100, 10_000)),
        )
        val request = AiRequest("GET", URI("$base/v1/slow"), emptyList(), null, deadline = Duration.ofMillis(500))
        val started = System.nanoTime()

        shouldThrow<IOException> { transport.execute(request).use { it.body.readAllBytes() } }

        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(3)
    }

    @Test
    fun `the OpenAI bridge reports a blocked destination as an SDK I-O failure`() {
        val bridge = OpenAiSdkHttpClient(GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test"))
        val request =
            HttpRequest
                .builder()
                .method(HttpMethod.GET)
                .baseUrl(base)
                .addPathSegment("models")
                .build()

        shouldThrow<OpenAIIoException> {
            bridge.execute(
                request,
            )
        }.cause.shouldBeInstanceOf<BlockedDestinationException>()
        val async = shouldThrow<ExecutionException> { bridge.executeAsync(request).get(5, TimeUnit.SECONDS) }
        async.cause.shouldBeInstanceOf<OpenAIIoException>()
    }

    @Test
    fun `the Anthropic bridge sends through the transport and reports failures as SDK I-O failures`() {
        provider.stubFor(get("/v1/models").willReturn(aResponse().withBody("""{"data":[]}""")))
        val bridge = AnthropicSdkHttpClient(transport)
        val request =
            AnthropicHttpRequest
                .builder()
                .method(
                    AnthropicHttpMethod.GET,
                ).baseUrl(base)
                .addPathSegments("v1", "models")
                .build()

        bridge.execute(request).use { it.statusCode() } shouldBe 200
        val blocked = AnthropicSdkHttpClient(GuardedAiTransport.create(DestinationAllowlist.NONE, "Jofi/test"))
        shouldThrow<AnthropicIoException> { blocked.execute(request) }
    }

    @Test
    fun `cancelling an SDK's future aborts the request that waits for headers`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val hungUp = CountDownLatch(2)
            Thread.ofVirtual().start { serveSilently(server, hungUp) }
            val silent = "http://127.0.0.1:${server.localPort}"
            val guarded =
                GuardedAiTransport.create(
                    DestinationAllowlist.of(listOf(Destination.of("127.0.0.1", server.localPort))),
                    "Jofi/test",
                )

            OpenAiSdkHttpClient(guarded).executeAsync(openAiGet(silent)).cancelSoon()
            AnthropicSdkHttpClient(guarded).executeAsync(anthropicGet(silent)).cancelSoon()

            hungUp.await(5, TimeUnit.SECONDS) shouldBe true
        }
    }

    private fun openAiGet(base: String): HttpRequest =
        HttpRequest
            .builder()
            .method(HttpMethod.GET)
            .baseUrl(base)
            .addPathSegment("models")
            .build()

    private fun anthropicGet(base: String): AnthropicHttpRequest =
        AnthropicHttpRequest
            .builder()
            .method(AnthropicHttpMethod.GET)
            .baseUrl(base)
            .addPathSegment("models")
            .build()

    /** Lets the request reach the server first, which then never answers. */
    private fun CompletableFuture<*>.cancelSoon() {
        Thread.sleep(300)
        cancel(true)
    }

    /** Accepts as many connections as [hungUp] counts and never answers them. */
    private fun serveSilently(
        server: ServerSocket,
        hungUp: CountDownLatch,
    ) {
        repeat(hungUp.count.toInt()) { _ ->
            server.accept().use { connection -> awaitHangUp(connection, hungUp) }
        }
    }

    /** Reads until the client closes the connection. */
    private fun awaitHangUp(
        connection: Socket,
        hungUp: CountDownLatch,
    ) {
        try {
            val buffer = ByteArray(1024)
            while (connection.getInputStream().read(buffer) != -1) {
                // Discard the request; the test only waits for the hang-up.
            }
        } catch (_: IOException) {
            // A reset counts as a hang-up too.
        } finally {
            hungUp.countDown()
        }
    }

    private infix fun Duration.shouldBeLessThan(limit: Duration) {
        (this < limit) shouldBe true
    }
}
