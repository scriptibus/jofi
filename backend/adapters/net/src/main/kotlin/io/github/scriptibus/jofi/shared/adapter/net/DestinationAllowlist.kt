// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.URI
import java.util.Locale

/**
 * A host and port as the guard sees them: host lowercased, without IPv6 brackets or a trailing
 * dot, port made explicit. Two URLs denote the same destination only if both match exactly, so
 * `localhost` and `127.0.0.1` are different destinations.
 */
data class Destination(
    val host: String,
    val port: Int,
) {
    companion object {
        private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

        fun of(
            host: String,
            port: Int,
        ): Destination = Destination(normalizeHost(host), port)

        /** The destination of an absolute http(s) URI, or null for anything else. */
        fun of(uri: URI): Destination? {
            val host = uri.host
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val port = if (uri.port != -1 && scheme in DEFAULT_PORTS) uri.port else DEFAULT_PORTS[scheme]
            return if (host == null || port == null) null else of(host, port)
        }

        fun normalizeHost(host: String): String =
            host.removeSurrounding("[", "]").removeSuffix(".").lowercase(Locale.ROOT)
    }
}

/**
 * Destinations that may resolve to internal addresses (loopback, private, shared, unique local;
 * see [AddressClass.unlockedByAllowlist]). Evaluated on every connection, so a changed AI provider
 * configuration takes effect without a restart. Link-local, metadata, multicast and reserved
 * addresses stay blocked even for an allowlisted destination (ADR-0034).
 */
fun interface DestinationAllowlist {
    fun permitsInternal(destination: Destination): Boolean

    companion object {
        /** The default for everything user- or posting-supplied: no internal destination at all. */
        val NONE: DestinationAllowlist = DestinationAllowlist { false }

        /** Exactly the given destinations, e.g. from a configured base URL. */
        fun of(destinations: Collection<Destination>): DestinationAllowlist {
            val allowed = destinations.toSet()
            return DestinationAllowlist { it in allowed }
        }
    }
}
