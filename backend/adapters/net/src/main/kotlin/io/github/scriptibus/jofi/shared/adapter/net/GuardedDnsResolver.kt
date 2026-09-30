// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.DnsResolver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration

/** Thrown by [GuardedDnsResolver] so the adapter can tell a blocked destination from a DNS failure. */
class BlockedDestinationException(
    val addressClass: AddressClass,
) : UnknownHostException("Destination not allowed ($addressClass)")

/** Thrown by [GuardedDnsResolver] when the lookup exceeds its budget; mapped to a timeout. */
class DnsTimeoutException : UnknownHostException("Host lookup timed out")

/**
 * The pinning point: Apache HttpClient calls this for every new connection and connects to exactly
 * the addresses it returns, so the addresses checked are the addresses used (no DNS rebinding).
 * TLS still verifies the certificate against the host name. [budget] bounds each lookup (a fetch
 * passes its remaining time).
 */
class GuardedDnsResolver(
    private val guard: DestinationGuard,
    private val budget: () -> Duration = { DestinationGuard.DEFAULT_RESOLUTION_TIME },
) : DnsResolver {
    override fun resolve(
        host: String,
        port: Int,
    ): List<InetSocketAddress> = guardedAddresses(Destination.of(host, port)).map { InetSocketAddress(it, port) }

    /** Without a port no allowlist entry can match, so only public addresses pass. */
    override fun resolve(host: String): Array<InetAddress> =
        guardedAddresses(Destination.of(host, UNKNOWN_PORT)).toTypedArray()

    /** No reverse or canonical-name lookups: they would be additional, unguarded DNS queries. */
    override fun resolveCanonicalHostname(host: String): String = host

    private fun guardedAddresses(destination: Destination): List<InetAddress> =
        when (val decision = guard.check(destination, budget())) {
            is GuardDecision.Allowed -> decision.addresses
            is GuardDecision.Blocked -> throw BlockedDestinationException(decision.addressClass)
            GuardDecision.Unresolvable -> throw UnknownHostException("Host not resolvable")
            GuardDecision.TimedOut -> throw DnsTimeoutException()
        }

    private companion object {
        const val UNKNOWN_PORT = -1
    }
}
