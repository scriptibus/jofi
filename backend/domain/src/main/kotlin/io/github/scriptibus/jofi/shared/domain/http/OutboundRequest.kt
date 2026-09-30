// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.domain.http

import java.net.URI
import java.time.Duration

/**
 * One outbound fetch (threat model T1). The URI often comes from the user or a posting, so it is
 * only required to be absolute here: scheme and address checks happen in the guard, which answers
 * with [FetchResult.Blocked] instead of failing, so callers can report a rejected link normally.
 *
 * [acceptedContentTypes] lists media types without parameters (e.g. `text/html`); empty accepts any.
 */
data class OutboundRequest(
    val uri: URI,
    val method: HttpMethod = HttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val acceptedContentTypes: Set<String> = emptySet(),
    val limits: FetchLimits = FetchLimits(),
) {
    init {
        require(uri.isAbsolute) { "An outbound request needs an absolute URI" }
        require(headers.keys.all { HEADER_NAME.matches(it) }) { "Header names must be HTTP tokens" }
        // CR, LF and NUL would let a value smuggle extra headers or requests (header injection).
        require(headers.values.none { value -> value.any { it in FORBIDDEN_IN_VALUES } }) {
            "Header values must not contain CR, LF or NUL"
        }
        require(acceptedContentTypes.none { it.isBlank() || ';' in it }) {
            "Accepted content types are bare media types"
        }
    }

    /**
     * Method, target host and header names only: header values can be credentials and the path or
     * query of a posting URL can identify the user (threat model T4).
     */
    override fun toString(): String =
        "OutboundRequest(method=$method, scheme=${uri.scheme}, host=${uri.host}, " +
            "headers=${headers.keys}, acceptedContentTypes=$acceptedContentTypes, limits=$limits)"

    private companion object {
        /** RFC 9110 `token`. */
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        val FORBIDDEN_IN_VALUES = setOf('\r', '\n', '\u0000')
    }
}

/** Read-only methods only: Jofi fetches postings, pages and APIs, it never posts to foreign sites. */
enum class HttpMethod {
    GET,
    HEAD,
}

/** Upper bounds of one fetch, redirects included. */
data class FetchLimits(
    val timeout: Duration = DEFAULT_TIMEOUT,
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {
    init {
        require(timeout > Duration.ZERO && timeout <= MAX_TIMEOUT) { "The timeout must be in (0, $MAX_TIMEOUT]" }
        require(maxBodyBytes in 1..MAX_BODY_BYTES) { "The body limit must be in 1..$MAX_BODY_BYTES bytes" }
        require(maxRedirects in 0..MAX_REDIRECTS) { "The redirect limit must be in 0..$MAX_REDIRECTS" }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        val MAX_TIMEOUT: Duration = Duration.ofMinutes(2)
        const val DEFAULT_MAX_BODY_BYTES: Long = 5L * 1024 * 1024
        const val MAX_BODY_BYTES: Long = 50L * 1024 * 1024
        const val DEFAULT_MAX_REDIRECTS: Int = 5
        const val MAX_REDIRECTS: Int = 10
    }
}
