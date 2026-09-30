// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.github.scriptibus.jofi.shared.domain.http.FetchResult
import io.github.scriptibus.jofi.shared.domain.http.FetchedResource
import io.github.scriptibus.jofi.shared.domain.http.HttpMethod
import io.github.scriptibus.jofi.shared.domain.http.OutboundRequest
import io.github.scriptibus.jofi.shared.domain.http.ResponseBody
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.HttpHeaders
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.time.Clock
import java.util.Locale
import kotlin.time.TimeMark

/** Signals that the fetch's overall deadline passed while reading; mapped to [FetchResult.Timeout]. */
internal class DeadlineExceededException : InterruptedIOException("Fetch deadline exceeded")

/** Turns one response into a result or a redirect: status, content type, then the size-limited body. */
internal class ResponseReader(
    private val request: OutboundRequest,
    private val clock: Clock,
    private val deadline: TimeMark,
) {
    private val acceptedTypes = request.acceptedContentTypes.map { it.lowercase(Locale.ROOT) }.toSet()
    private val maxBytes = request.limits.maxBodyBytes

    fun interpret(
        target: URI,
        response: ClassicHttpResponse,
    ): HopOutcome {
        val status = response.code
        val location = response.getFirstHeader(HttpHeaders.LOCATION)?.value
        return when {
            status in REDIRECT_STATUSES && location != null -> {
                FetchTarget.resolveLocation(target, location)?.let { HopOutcome.Redirect(it) }
                    ?: HopOutcome.Done(FetchResult.HttpError(status))
            }

            status !in SUCCESS -> {
                HopOutcome.Done(httpError(status, response))
            }

            else -> {
                HopOutcome.Done(success(target, response))
            }
        }
    }

    private fun httpError(
        status: Int,
        response: ClassicHttpResponse,
    ): FetchResult.HttpError =
        FetchResult.HttpError(status, RetryAfter.parse(response.getFirstHeader(HttpHeaders.RETRY_AFTER)?.value, clock))

    private fun success(
        target: URI,
        response: ClassicHttpResponse,
    ): FetchResult {
        val contentType = response.getFirstHeader(HttpHeaders.CONTENT_TYPE)?.value
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (acceptedTypes.isNotEmpty() && mediaType !in acceptedTypes) {
            return FetchResult.ContentTypeNotAccepted(mediaType)
        }
        val body = readBody(response)
        return if (body == null) {
            FetchResult.TooLarge(maxBytes)
        } else {
            FetchResult.Success(FetchedResource(target, response.code, contentType, ResponseBody(body)))
        }
    }

    /** The body, or null if it is larger than allowed. */
    private fun readBody(response: ClassicHttpResponse): ByteArray? {
        val entity = response.entity
        return when {
            request.method == HttpMethod.HEAD || entity == null -> ByteArray(0)

            // A declared length over the limit is refused before reading a single byte.
            entity.contentLength > maxBytes -> null

            // Not closed here: closing the stream drains the rest of the body (possibly endless).
            // The caller discards the connection instead.
            else -> readWithinLimits(entity.content)
        }
    }

    /** The body, or null once it grows past the limit (decompressed size counts). */
    private fun readWithinLimits(stream: InputStream): ByteArray? {
        val buffer = ByteArray(BUFFER_BYTES)
        val body = ByteArrayOutputStream()
        while (true) {
            if (deadline.hasPassedNow()) throw DeadlineExceededException()
            val read = stream.read(buffer)
            if (read == -1) return body.toByteArray()
            if (body.size().toLong() + read > maxBytes) return null
            body.write(buffer, 0, read)
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
        val SUCCESS = 200..299
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
