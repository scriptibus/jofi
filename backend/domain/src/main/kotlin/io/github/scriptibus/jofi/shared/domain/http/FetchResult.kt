// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.http

import java.net.URI
import java.time.Duration

/**
 * Outcome of an outbound fetch. Every expected failure is a variant, never an exception. Only
 * status codes, limits and reasons are carried, never response bodies, so results are safe to log.
 */
sealed interface FetchResult {
    /** A 2xx answer within all limits. */
    data class Success(
        val resource: FetchedResource,
    ) : FetchResult

    /** The guard refused the target (or a redirect hop) before sending anything to it. */
    data class Blocked(
        val reason: BlockReason,
    ) : FetchResult

    /** Connecting or reading took longer than the request's timeout. */
    data object Timeout : FetchResult

    /** The body exceeded [limitBytes]; reading stopped there. */
    data class TooLarge(
        val limitBytes: Long,
    ) : FetchResult

    /** More redirects than [limit]. */
    data class TooManyRedirects(
        val limit: Int,
    ) : FetchResult

    /** The answer's content type is not among the accepted ones. */
    data class ContentTypeNotAccepted(
        val contentType: String?,
    ) : FetchResult

    /** The server answered with a non-2xx status after redirects; [retryAfter] when it sent `Retry-After`. */
    data class HttpError(
        val statusCode: Int,
        val retryAfter: Duration? = null,
    ) : FetchResult

    /** DNS resolution or the connection failed. */
    data object Unreachable : FetchResult
}

/** Why the guard refused a target. */
enum class BlockReason {
    /** Anything but `http` and `https` (`file:`, `ftp:`, `jar:`, ...). */
    SCHEME_NOT_ALLOWED,

    /** The host resolves to a loopback, private, link-local or otherwise internal address. */
    ADDRESS_NOT_ALLOWED,
}

/** A fetched document: where it ended up after redirects, its status, media type and body. */
data class FetchedResource(
    val finalUri: URI,
    val statusCode: Int,
    val contentType: String?,
    val body: ResponseBody,
) {
    init {
        require(statusCode in SUCCESS_RANGE) { "A fetched resource has a 2xx status" }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
    }
}

/**
 * Raw response bytes. A defensive copy keeps it immutable; [toString] shows only the size, because
 * bodies can contain personal data that must not reach logs (threat model T4).
 */
class ResponseBody(
    bytes: ByteArray,
) {
    private val content: ByteArray = bytes.copyOf()

    val size: Int get() = content.size

    /** A copy of the bytes. */
    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean = other is ResponseBody && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "ResponseBody($size bytes)"
}
