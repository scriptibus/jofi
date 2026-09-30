// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.shared.domain.http.FetchLimits
import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/** The overall deadline and the size cap under hostile servers and resolvers, measured in wall time. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboundHttpLimitsTest {
    // Compression disabled, so WireMock sends the gzip body below exactly as given.
    private val server =
        WireMockServer(wireMockConfig().dynamicPort().bindAddress(LOOPBACK).gzipDisabled(true)).apply { start() }
    private val other = WireMockServer(wireMockConfig().dynamicPort().bindAddress(LOOPBACK)).apply { start() }

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
    fun `a name with many unroutable addresses cannot stretch the deadline`() {
        // Eight "public" addresses that never answer (the test classifies them as public).
        val blackholes = (1..8).map { InetAddress.ofLiteral("10.255.255.$it") }
        val guard = DestinationGuard(resolver = { blackholes }, classify = { AddressClass.PUBLIC })
        val adapter = OutboundHttpAdapter(guard, USER_AGENT)

        val (result, elapsed) = timed { adapter.fetch(request("http://blackhole.example:81/", timeoutMillis = 700)) }

        result shouldBeIn listOf(FetchResult.Timeout, FetchResult.Unreachable)
        elapsed shouldBeLessThan Duration.ofMillis(700 + SLACK_MILLIS)
    }

    @Test
    fun `a slow DNS server cannot stretch the deadline`() {
        val guard =
            DestinationGuard(resolver = {
                Thread.sleep(10_000)
                emptyList()
            })

        val (result, elapsed) =
            timed {
                OutboundHttpAdapter(guard, USER_AGENT).fetch(request("https://slow-dns.example/", 500))
            }

        result shouldBe FetchResult.Timeout
        elapsed shouldBeLessThan Duration.ofMillis(500 + SLACK_MILLIS)
    }

    @Test
    fun `a body dribbled under the size cap is cut off at the deadline`() {
        server.stubFor(get("/drip").willReturn(aResponse().withBody(ByteArray(200)).withChunkedDribbleDelay(20, 4_000)))

        val (result, elapsed) = timed { allowing(server.port()).fetch(request(url(server, "/drip"), 600)) }

        result shouldBe FetchResult.Timeout
        elapsed shouldBeLessThan Duration.ofMillis(600 + SLACK_MILLIS)
    }

    @Test
    fun `a gzip bomb is cut off at the size cap after decompression`() {
        val bomb = gzip(ByteArray(12 * 1024 * 1024))
        bomb.size.toLong() shouldBeLessThan 64 * 1024L
        server.stubFor(get("/bomb").willReturn(aResponse().withHeader("Content-Encoding", "gzip").withBody(bomb)))

        val result = allowing(server.port()).fetch(request(url(server, "/bomb"), maxBodyBytes = 1024 * 1024))

        result shouldBe FetchResult.TooLarge(1024 * 1024)
    }

    @Test
    fun `a declared length over the cap is refused without reading the body`() {
        // Identity encoding, and the body never comes: reading it would wait for the whole timeout.
        RawHttpServer { out, _ ->
            out.write(head("Content-Length: 50000000"))
            out.flush()
            Thread.sleep(10_000)
        }.use { raw ->
            val (result, elapsed) =
                timed {
                    allowing(
                        raw.port,
                    ).fetch(request("http://$LOOPBACK:${raw.port}/", 5_000, 1024))
                }

            result shouldBe FetchResult.TooLarge(1024)
            elapsed shouldBeLessThan Duration.ofMillis(SLACK_MILLIS)
        }
    }

    @Test
    fun `an endless body is abandoned at the cap, not drained`() {
        RawHttpServer { out, sent -> endlessChunks(out, sent) }.use { raw ->
            val (result, elapsed) =
                timed {
                    allowing(
                        raw.port,
                    ).fetch(request("http://$LOOPBACK:${raw.port}/", 10_000, 1024))
                }

            result shouldBe FetchResult.TooLarge(1024)
            elapsed shouldBeLessThan Duration.ofMillis(SLACK_MILLIS)
            val sentAtReturn = raw.bytesSent.get()
            Thread.sleep(300)
            // The connection is gone: the server cannot keep writing into it.
            raw.bytesSent.get() - sentAtReturn shouldBeLessThan 4L * 1024 * 1024
        }
    }

    @Test
    fun `JVM proxy settings are ignored`() {
        other.stubFor(get(anyUrl()).willReturn(aResponse().withBody("proxy")))
        server.stubFor(get("/direct").willReturn(aResponse().withBody("direct")))
        val saved = PROXY_PROPERTIES.associateWith { System.getProperty(it) }
        try {
            listOf("http", "https").forEach { scheme ->
                System.setProperty("$scheme.proxyHost", LOOPBACK)
                System.setProperty("$scheme.proxyPort", "${other.port()}")
            }
            // The JDK default would bypass the proxy for loopback; make it apply everywhere.
            System.setProperty("http.nonProxyHosts", "")

            val result = allowing(server.port()).fetch(request(url(server, "/direct")))

            result
                .shouldBeInstanceOf<FetchResult.Success>()
                .resource.body
                .bytes()
                .decodeToString() shouldBe "direct"
            other.verify(0, anyRequestedFor(anyUrl()))
        } finally {
            saved.forEach { (key, value) ->
                if (value ==
                    null
                ) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }

    @Test
    fun `a relative redirect from an empty path resolves against the root`() {
        server.stubFor(get("/").willReturn(aResponse().withStatus(302).withHeader("Location", "next")))
        server.stubFor(get("/next").willReturn(aResponse().withBody("ok")))

        val result = allowing(server.port()).fetch(request("http://$LOOPBACK:${server.port()}"))

        result.shouldBeInstanceOf<FetchResult.Success>().resource.finalUri shouldBe URI(url(server, "/next"))
        server.verify(getRequestedFor(urlEqualTo("/next")))
    }

    private fun allowing(port: Int) =
        OutboundHttpAdapter(
            DestinationGuard(DestinationAllowlist.of(listOf(Destination.of(LOOPBACK, port)))),
            USER_AGENT,
        )

    private fun request(
        uri: String,
        timeoutMillis: Long = 5_000,
        maxBodyBytes: Long = FetchLimits.DEFAULT_MAX_BODY_BYTES,
    ) = OutboundRequest(URI(uri), limits = FetchLimits(Duration.ofMillis(timeoutMillis), maxBodyBytes))

    private fun url(
        target: WireMockServer,
        path: String,
    ) = "http://$LOOPBACK:${target.port()}$path"

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val started = System.nanoTime()
        val value = block()
        return value to Duration.ofNanos(System.nanoTime() - started)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()

    private fun head(vararg headers: String): ByteArray =
        (
            listOf(
                "HTTP/1.1 200 OK",
                "Content-Type: text/plain",
            ) + headers
        ).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray()

    private fun endlessChunks(
        out: OutputStream,
        sent: AtomicLong,
    ) {
        out.write(head("Transfer-Encoding: chunked"))
        val chunk = "1000\r\n".toByteArray() + ByteArray(4096) { 'x'.code.toByte() } + "\r\n".toByteArray()
        while (true) {
            out.write(chunk)
            sent.addAndGet(chunk.size.toLong())
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val USER_AGENT = "Jofi/test"

        /** Scheduling and JIT noise on a loaded CI runner; the guarded bounds are far below the servers' delays. */
        const val SLACK_MILLIS = 700L
        val PROXY_PROPERTIES =
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts")
    }
}
