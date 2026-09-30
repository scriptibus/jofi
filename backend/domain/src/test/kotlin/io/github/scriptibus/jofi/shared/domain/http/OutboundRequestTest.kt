// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

class OutboundRequestTest {
    private val posting = URI("https://jobs.example.org/postings/42")

    @Test
    fun `defaults to a bounded GET`() {
        val request = OutboundRequest(posting)

        request.method shouldBe HttpMethod.GET
        request.limits shouldBe
            FetchLimits(
                FetchLimits.DEFAULT_TIMEOUT,
                FetchLimits.DEFAULT_MAX_BODY_BYTES,
                FetchLimits.DEFAULT_MAX_REDIRECTS,
            )
    }

    @Test
    fun `accepts any absolute URI, so the guard can answer Blocked for bad schemes`() {
        OutboundRequest(URI("file:///etc/passwd")).uri.scheme shouldBe "file"
        shouldThrow<IllegalArgumentException> { OutboundRequest(URI("/relative/path")) }
    }

    @Test
    fun `validates header names and accepted content types`() {
        OutboundRequest(posting, headers = mapOf("Accept-Language" to "de")).headers.size shouldBe 1
        shouldThrow<IllegalArgumentException> { OutboundRequest(posting, headers = mapOf("Bad Header" to "x")) }
        shouldThrow<IllegalArgumentException> { OutboundRequest(posting, headers = mapOf("X\r\nInjected" to "x")) }
        OutboundRequest(posting, acceptedContentTypes = setOf("text/html")).acceptedContentTypes shouldBe
            setOf("text/html")
        shouldThrow<IllegalArgumentException> { OutboundRequest(posting, acceptedContentTypes = setOf(" ")) }
        shouldThrow<IllegalArgumentException> {
            OutboundRequest(posting, acceptedContentTypes = setOf("text/html; charset=utf-8"))
        }
    }

    @Test
    fun `limits stay within their bounds`() {
        FetchLimits(timeout = FetchLimits.MAX_TIMEOUT, maxBodyBytes = 1, maxRedirects = 0).maxRedirects shouldBe 0
        shouldThrow<IllegalArgumentException> { FetchLimits(timeout = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { FetchLimits(timeout = FetchLimits.MAX_TIMEOUT.plusSeconds(1)) }
        shouldThrow<IllegalArgumentException> { FetchLimits(maxBodyBytes = 0) }
        shouldThrow<IllegalArgumentException> { FetchLimits(maxBodyBytes = FetchLimits.MAX_BODY_BYTES + 1) }
        shouldThrow<IllegalArgumentException> { FetchLimits(maxRedirects = -1) }
        shouldThrow<IllegalArgumentException> { FetchLimits(maxRedirects = FetchLimits.MAX_REDIRECTS + 1) }
    }

    @Test
    fun `a fetched resource has a 2xx status`() {
        val body = ResponseBody("<html/>".toByteArray())

        FetchedResource(posting, 200, "text/html", body).statusCode shouldBe 200
        shouldThrow<IllegalArgumentException> { FetchedResource(posting, 199, null, body) }
        shouldThrow<IllegalArgumentException> { FetchedResource(posting, 301, null, body) }
    }

    @Test
    fun `a response body is an immutable copy that never prints its content`() {
        val original = "Max Mustermann".toByteArray()
        val body = ResponseBody(original)
        original[0] = 'X'.code.toByte()

        body.bytes().decodeToString() shouldBe "Max Mustermann"
        body.bytes().also { it[0] = 'Y'.code.toByte() }
        body shouldBe ResponseBody("Max Mustermann".toByteArray())
        body.hashCode() shouldBe ResponseBody("Max Mustermann".toByteArray()).hashCode()
        body shouldNotBe ResponseBody("other".toByteArray())
        body.size shouldBe 14
        body.toString() shouldNotContain "Max"
    }

    @Test
    fun `every fetch outcome is a value`() {
        val results =
            listOf(
                FetchResult.Success(FetchedResource(posting, 200, null, ResponseBody(ByteArray(0)))),
                FetchResult.Blocked(BlockReason.SCHEME_NOT_ALLOWED),
                FetchResult.Blocked(BlockReason.ADDRESS_NOT_ALLOWED),
                FetchResult.Timeout,
                FetchResult.TooLarge(1024),
                FetchResult.TooManyRedirects(5),
                FetchResult.ContentTypeNotAccepted("application/pdf"),
                FetchResult.HttpError(404),
                FetchResult.Unreachable,
            )

        results.map(::describe) shouldBe
            listOf(
                "200",
                "SCHEME_NOT_ALLOWED",
                "ADDRESS_NOT_ALLOWED",
                "timeout",
                "1024",
                "5",
                "application/pdf",
                "404",
                "unreachable",
            )
    }

    private fun describe(result: FetchResult): String =
        when (result) {
            is FetchResult.Success -> result.resource.statusCode.toString()
            is FetchResult.Blocked -> result.reason.name
            FetchResult.Timeout -> "timeout"
            is FetchResult.TooLarge -> result.limitBytes.toString()
            is FetchResult.TooManyRedirects -> result.limit.toString()
            is FetchResult.ContentTypeNotAccepted -> result.contentType.toString()
            is FetchResult.HttpError -> result.statusCode.toString()
            FetchResult.Unreachable -> "unreachable"
        }
}
