// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.head
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import io.github.scriptibus.jofi.shared.domain.http.FetchLimits
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.HttpMethod
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The adapter against a real HTTP server. WireMock listens on loopback, which the guard blocks, so
 * most tests allowlist exactly its destination; the blocking tests show what happens without that.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboundHttpAdapterTest {
    private val server = WireMockServer(wireMockConfig().dynamicPort().bindAddress(LOOPBACK)).apply { start() }
    private val other = WireMockServer(wireMockConfig().dynamicPort().bindAddress(LOOPBACK)).apply { start() }
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private val adapter = adapterAllowing(Destination.of(LOOPBACK, server.port()))

    @BeforeEach
    fun reset() {
        server.resetAll()
        other.resetAll()
    }

    @AfterAll
    fun stop() {
        server.stop()
        other.stop()
    }

    @Test
    fun `fetches a page with an identifying user agent and the caller's headers`() {
        server.stubFor(
            get(
                "/job",
            ).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody("<h1>Job</h1>")),
        )

        val result = adapter.fetch(OutboundRequest(url("/job"), headers = mapOf("Accept-Language" to "de")))

        val resource = result.shouldBeInstanceOf<FetchResult.Success>().resource
        resource.body.bytes().decodeToString() shouldBe "<h1>Job</h1>"
        resource.contentType shouldBe "text/html; charset=utf-8"
        resource.finalUri shouldBe url("/job")
        server.verify(
            getRequestedFor(urlEqualTo("/job"))
                .withHeader("User-Agent", equalTo(USER_AGENT))
                .withHeader("Accept-Language", equalTo("de")),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["file:///etc/passwd", "ftp://example.org/file", "jar:file:/app.jar!/x", "gopher://example.org/"],
    )
    fun `rejects every scheme but http and https`(uri: String) {
        adapter.fetch(OutboundRequest(URI(uri))) shouldBe FetchResult.Blocked(BlockReason.SCHEME_NOT_ALLOWED)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1:%d/",
            "http://localhost:%d/",
            "http://[::ffff:127.0.0.1]:%d/",
            "http://0.0.0.0:%d/",
        ],
    )
    fun `without an allowlist entry a loopback server is never contacted`(template: String) {
        server.stubFor(get(anyUrl()).willReturn(aResponse().withBody("internal")))
        val guarded = OutboundHttpAdapter(DestinationGuard(), "Jofi/test")

        guarded.fetch(OutboundRequest(URI(template.replace("%d", "${server.port()}")))) shouldBe
            FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        server.verify(0, anyRequestedFor(anyUrl()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://169.254.169.254/latest/meta-data/",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://10.0.0.1/admin",
            "http://127.0.0.1:%d/internal",
            "http://[::1]:%d/internal",
        ],
    )
    fun `a redirect to an internal address is blocked`(location: String) {
        other.stubFor(get(anyUrl()).willReturn(aResponse().withBody("internal")))
        val target = location.replace("%d", "${other.port()}")
        server.stubFor(get("/jump").willReturn(aResponse().withStatus(302).withHeader("Location", target)))

        adapter.fetch(OutboundRequest(url("/jump"))) shouldBe FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED)
        other.verify(0, anyRequestedFor(anyUrl()))
    }

    @Test
    fun `a redirect to a forbidden scheme is blocked`() {
        server.stubFor(
            get("/jump").willReturn(aResponse().withStatus(301).withHeader("Location", "file:///etc/passwd")),
        )

        adapter.fetch(OutboundRequest(url("/jump"))) shouldBe FetchResult.Blocked(BlockReason.SCHEME_NOT_ALLOWED)
    }

    @Test
    fun `follows relative redirects and reports the final URI without its fragment`() {
        server.stubFor(get("/a").willReturn(aResponse().withStatus(301).withHeader("Location", "/b")))
        server.stubFor(get("/b").willReturn(aResponse().withStatus(307).withHeader("Location", "c?x=1#top")))
        server.stubFor(get("/c?x=1").willReturn(aResponse().withBody("done")))

        val result = adapter.fetch(OutboundRequest(url("/a")))

        result.shouldBeInstanceOf<FetchResult.Success>().resource.finalUri shouldBe url("/c?x=1")
    }

    @Test
    fun `stops after the redirect limit`() {
        server.stubFor(get("/loop").willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")))

        val result = adapter.fetch(OutboundRequest(url("/loop"), limits = FetchLimits(maxRedirects = 3)))

        result shouldBe FetchResult.TooManyRedirects(3)
        server.verify(4, getRequestedFor(urlEqualTo("/loop")))
    }

    @Test
    fun `a cross-origin redirect drops credentials but keeps harmless headers`() {
        val bothServers =
            adapterAllowing(Destination.of(LOOPBACK, server.port()), Destination.of(LOOPBACK, other.port()))
        server.stubFor(
            get(
                "/a",
            ).willReturn(aResponse().withStatus(302).withHeader("Location", "http://$LOOPBACK:${other.port()}/b")),
        )
        other.stubFor(get("/b").willReturn(aResponse().withBody("ok")))
        val headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "session=1", "Accept" to "text/html")

        bothServers.fetch(OutboundRequest(url("/a"), headers = headers)).shouldBeInstanceOf<FetchResult.Success>()

        server.verify(getRequestedFor(urlEqualTo("/a")).withHeader("Authorization", equalTo("Bearer secret")))
        other.verify(
            getRequestedFor(urlEqualTo("/b"))
                .withHeader("Authorization", absent())
                .withHeader("Cookie", absent())
                .withHeader("Accept", equalTo("text/html")),
        )
    }

    @Test
    fun `refuses a body whose declared length exceeds the limit`() {
        server.stubFor(get("/big").willReturn(aResponse().withBody(ByteArray(2048))))

        adapter.fetch(OutboundRequest(url("/big"), limits = FetchLimits(maxBodyBytes = 1024))) shouldBe
            FetchResult.TooLarge(1024)
    }

    @Test
    fun `stops reading a chunked body at the limit`() {
        server.stubFor(
            get("/stream").willReturn(aResponse().withBody(ByteArray(64 * 1024)).withChunkedDribbleDelay(8, 50)),
        )

        adapter.fetch(OutboundRequest(url("/stream"), limits = FetchLimits(maxBodyBytes = 1024))) shouldBe
            FetchResult.TooLarge(1024)
    }

    @Test
    fun `accepts a body of exactly the limit`() {
        server.stubFor(get("/fits").willReturn(aResponse().withBody(ByteArray(1024))))

        val result = adapter.fetch(OutboundRequest(url("/fits"), limits = FetchLimits(maxBodyBytes = 1024)))

        result
            .shouldBeInstanceOf<FetchResult.Success>()
            .resource.body.size shouldBe 1024
    }

    @Test
    fun `times out on a slow server`() {
        server.stubFor(get("/slow").willReturn(aResponse().withFixedDelay(3_000).withBody("late")))

        val result =
            adapter.fetch(
                OutboundRequest(url("/slow"), limits = FetchLimits(timeout = Duration.ofMillis(300))),
            )

        result shouldBe FetchResult.Timeout
    }

    @Test
    fun `refuses a content type that was not asked for`() {
        server.stubFor(
            get("/doc").willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody("%PDF")),
        )

        val result = adapter.fetch(OutboundRequest(url("/doc"), acceptedContentTypes = setOf("text/html")))

        result shouldBe FetchResult.ContentTypeNotAccepted("application/pdf")
    }

    @Test
    fun `matches accepted content types without parameters and case-insensitively`() {
        server.stubFor(
            get(
                "/page",
            ).willReturn(aResponse().withHeader("Content-Type", "Text/HTML; charset=ISO-8859-1").withBody("x")),
        )

        val result = adapter.fetch(OutboundRequest(url("/page"), acceptedContentTypes = setOf("text/html")))

        result.shouldBeInstanceOf<FetchResult.Success>()
    }

    @Test
    fun `reports an HTTP error with Retry-After in seconds`() {
        server.stubFor(get("/busy").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")))

        adapter.fetch(OutboundRequest(url("/busy"))) shouldBe FetchResult.HttpError(429, Duration.ofSeconds(120))
    }

    @Test
    fun `reports an HTTP error with Retry-After as a date`() {
        server.stubFor(
            get(
                "/down",
            ).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "Wed, 30 Sep 2026 12:05:00 GMT")),
        )

        adapter.fetch(OutboundRequest(url("/down"))) shouldBe FetchResult.HttpError(503, Duration.ofMinutes(5))
    }

    @Test
    fun `reports an HTTP error without Retry-After`() {
        server.stubFor(get("/gone").willReturn(aResponse().withStatus(404).withBody("not here")))

        adapter.fetch(OutboundRequest(url("/gone"))) shouldBe FetchResult.HttpError(404)
    }

    @Test
    fun `a HEAD request returns no body`() {
        server.stubFor(head(urlEqualTo("/head")).willReturn(aResponse().withHeader("Content-Type", "text/html")))

        val result = adapter.fetch(OutboundRequest(url("/head"), method = HttpMethod.HEAD))

        result
            .shouldBeInstanceOf<FetchResult.Success>()
            .resource.body.size shouldBe 0
    }

    @Test
    fun `strips user info from the URL`() {
        server.stubFor(get("/private").willReturn(aResponse().withBody("ok")))

        val result = adapter.fetch(OutboundRequest(URI("http://user:pass@$LOOPBACK:${server.port()}/private")))

        result.shouldBeInstanceOf<FetchResult.Success>()
        server.verify(getRequestedFor(urlEqualTo("/private")).withHeader("Authorization", absent()))
    }

    @Test
    fun `an unresolvable host or a closed port is unreachable`() {
        val closedPort =
            WireMockServer(wireMockConfig().dynamicPort().bindAddress(LOOPBACK)).run {
                start()
                port().also { stop() }
            }
        val unresolvable =
            OutboundHttpAdapter(DestinationGuard(resolver = { throw UnknownHostException(it) }), "Jofi/test")

        unresolvable.fetch(OutboundRequest(URI("https://jobs.example/"))) shouldBe FetchResult.Unreachable
        val closed = adapterAllowing(Destination.of(LOOPBACK, closedPort))
        closed.fetch(OutboundRequest(URI("http://$LOOPBACK:$closedPort/"))) shouldBe FetchResult.Unreachable
    }

    @Test
    fun `connects to the address that was checked, not to a rebound one`() {
        // The attacker's name first resolves to an "external" address (nothing listens there), then
        // to the internal server. A guard that checks one lookup and connects with another would
        // reach the internal server; ours connects only to the checked address.
        server.stubFor(get(anyUrl()).willReturn(aResponse().withBody("internal")))
        val answers = ArrayDeque(listOf(EXTERNAL_STAND_IN, LOOPBACK))
        var lookups = 0
        val guard =
            DestinationGuard(
                resolver = {
                    lookups++
                    listOf(InetAddress.ofLiteral(answers.removeFirst()))
                },
                classify = { address ->
                    if (address.hostAddress ==
                        EXTERNAL_STAND_IN
                    ) {
                        AddressClass.PUBLIC
                    } else {
                        AddressClassifier.classify(address)
                    }
                },
            )

        val rebinding = OutboundHttpAdapter(guard, "Jofi/test")
        val result = rebinding.fetch(OutboundRequest(URI("http://rebind.example:${server.port()}/")))

        result shouldBe FetchResult.Unreachable
        lookups shouldBe 1
        server.verify(0, anyRequestedFor(anyUrl()))
    }

    private fun url(path: String): URI = URI("http://$LOOPBACK:${server.port()}$path")

    private fun adapterAllowing(vararg destinations: Destination): OutboundHttpAdapter =
        OutboundHttpAdapter(DestinationGuard(DestinationAllowlist.of(destinations.toList())), USER_AGENT, clock)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val USER_AGENT = "Jofi/test (+https://github.com/scriptibus/jofi)"

        /** Another loopback address the test classifies as public; nothing listens on it. */
        const val EXTERNAL_STAND_IN = "127.0.0.2"
    }
}
