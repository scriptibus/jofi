// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.github.scriptibus.jofi.shared.domain.http.BlockReason
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** URL checks the guard can make before any DNS lookup: scheme allowlist and a usable host. */
internal object FetchTarget {
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /** Why [uri] may not be fetched at all, or null if it may (its addresses are checked on connect). */
    fun blockReason(uri: URI): BlockReason? =
        when {
            uri.scheme?.lowercase(Locale.ROOT) !in ALLOWED_SCHEMES -> BlockReason.SCHEME_NOT_ALLOWED
            uri.host.isNullOrBlank() -> BlockReason.ADDRESS_NOT_ALLOWED
            else -> null
        }

    /**
     * [uri] without user info and fragment. Credentials in a URL would travel to whatever host it
     * names (and Apache HttpClient rejects them); the fragment never goes on the wire.
     */
    fun sanitize(uri: URI): URI =
        if (uri.rawUserInfo == null && uri.rawFragment == null) {
            uri
        } else {
            URI(
                buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port != -1) append(':').append(uri.port)
                    append(uri.rawPath.orEmpty())
                    uri.rawQuery?.let { append('?').append(it) }
                },
            )
        }

    /** The absolute target of a `Location` header, or null if it is not a valid URI reference. */
    fun resolveLocation(
        current: URI,
        location: String,
    ): URI? =
        try {
            current.resolve(URI(location.trim()))
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Same scheme, host and port (RFC 6454 origin). */
    fun isSameOrigin(
        first: URI,
        second: URI,
    ): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) && Destination.of(first) == Destination.of(second)
}
