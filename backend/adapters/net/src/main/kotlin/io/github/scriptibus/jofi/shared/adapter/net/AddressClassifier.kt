// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * What kind of address a host resolved to. Only [PUBLIC] is reachable by default. The internal
 * kinds with [unlockedByAllowlist] set may be reached for an explicitly allowlisted destination (a local
 * Ollama, ADR-0034); the others are never reachable.
 */
enum class AddressClass(
    val unlockedByAllowlist: Boolean,
) {
    PUBLIC(unlockedByAllowlist = false),
    LOOPBACK(unlockedByAllowlist = true),

    /** RFC 1918. */
    PRIVATE(unlockedByAllowlist = true),

    /** RFC 6598 shared address space (carrier-grade NAT, also used by Tailscale). */
    SHARED(unlockedByAllowlist = true),

    /** RFC 4193 unique local IPv6 (`fc00::/7`). */
    UNIQUE_LOCAL(unlockedByAllowlist = true),

    /** `169.254.0.0/16` and `fe80::/10`, where cloud metadata services live. */
    LINK_LOCAL(unlockedByAllowlist = false),

    /** Cloud metadata endpoints outside link-local ranges (Alibaba, Azure WireServer, AWS IPv6). */
    CLOUD_METADATA(unlockedByAllowlist = false),
    MULTICAST(unlockedByAllowlist = false),
    UNSPECIFIED(unlockedByAllowlist = false),

    /** Documentation, benchmarking, broadcast, deprecated transition and unassigned ranges. */
    RESERVED(unlockedByAllowlist = false),
}

/**
 * Classifies IP addresses along the IANA special-purpose registries (RFC 6890 and successors).
 * IPv6 addresses that embed an IPv4 address (IPv4-mapped, NAT64 well-known prefix) are classified
 * by the embedded address, so `::ffff:127.0.0.1` is loopback. IPv6 outside global unicast
 * (`2000::/3`) is never public.
 */
object AddressClassifier {
    private val IPV4 =
        ranges(
            // Metadata services outside link-local, before the broader ranges that contain them:
            // Alibaba Cloud (inside CGNAT space) and Azure WireServer (a public address).
            "100.100.100.200/32" to AddressClass.CLOUD_METADATA,
            "168.63.129.16/32" to AddressClass.CLOUD_METADATA,
            "0.0.0.0/8" to AddressClass.UNSPECIFIED,
            "10.0.0.0/8" to AddressClass.PRIVATE,
            "100.64.0.0/10" to AddressClass.SHARED,
            "127.0.0.0/8" to AddressClass.LOOPBACK,
            "169.254.0.0/16" to AddressClass.LINK_LOCAL,
            "172.16.0.0/12" to AddressClass.PRIVATE,
            "192.0.0.0/24" to AddressClass.RESERVED,
            "192.0.2.0/24" to AddressClass.RESERVED,
            "192.88.99.0/24" to AddressClass.RESERVED,
            "192.168.0.0/16" to AddressClass.PRIVATE,
            "198.18.0.0/15" to AddressClass.RESERVED,
            "198.51.100.0/24" to AddressClass.RESERVED,
            "203.0.113.0/24" to AddressClass.RESERVED,
            "224.0.0.0/4" to AddressClass.MULTICAST,
            "240.0.0.0/4" to AddressClass.RESERVED,
        )

    private val IPV6 =
        ranges(
            "::/128" to AddressClass.UNSPECIFIED,
            "::1/128" to AddressClass.LOOPBACK,
            // Deprecated IPv4-compatible addresses (::a.b.c.d).
            "::/96" to AddressClass.RESERVED,
            "64:ff9b:1::/48" to AddressClass.RESERVED,
            "100::/64" to AddressClass.RESERVED,
            // IETF protocol assignments, including Teredo (2001::/32), which tunnels to IPv4.
            "2001::/23" to AddressClass.RESERVED,
            "2001:db8::/32" to AddressClass.RESERVED,
            // 6to4 tunnels to arbitrary IPv4 addresses.
            "2002::/16" to AddressClass.RESERVED,
            "3fff::/20" to AddressClass.RESERVED,
            "fd00:ec2::254/128" to AddressClass.CLOUD_METADATA,
            "fc00::/7" to AddressClass.UNIQUE_LOCAL,
            "fe80::/10" to AddressClass.LINK_LOCAL,
            // Deprecated site-local.
            "fec0::/10" to AddressClass.RESERVED,
            "ff00::/8" to AddressClass.MULTICAST,
        )

    private val GLOBAL_UNICAST = AddressRange.parse("2000::/3")
    private val NAT64_WELL_KNOWN = AddressRange.parse("64:ff9b::/96")
    private val IPV4_MAPPED_PREFIX = ByteArray(IPV6_BYTES - IPV4_BYTES) { if (it >= MAPPED_MARKER_START) -1 else 0 }

    fun classify(address: InetAddress): AddressClass =
        when (address) {
            is Inet4Address -> classifyIpv4(address.address)
            is Inet6Address -> classifyIpv6(address.address)
            else -> AddressClass.RESERVED
        }

    private fun classifyIpv4(bytes: ByteArray): AddressClass =
        IPV4.firstOrNull { (range, _) -> range.contains(bytes) }?.second ?: AddressClass.PUBLIC

    private fun classifyIpv6(bytes: ByteArray): AddressClass {
        val embedded = embeddedIpv4(bytes)
        // Most specific first: the table lists narrow blocks before the ranges that contain them.
        val special = IPV6.firstOrNull { (range, _) -> range.contains(bytes) }?.second
        return when {
            embedded != null -> classifyIpv4(embedded)
            special != null -> special
            GLOBAL_UNICAST.contains(bytes) -> AddressClass.PUBLIC
            else -> AddressClass.RESERVED
        }
    }

    private fun embeddedIpv4(bytes: ByteArray): ByteArray? {
        val prefix = bytes.copyOfRange(0, IPV6_BYTES - IPV4_BYTES)
        val embedded = prefix.contentEquals(IPV4_MAPPED_PREFIX) || NAT64_WELL_KNOWN.contains(bytes)
        return if (embedded) bytes.copyOfRange(IPV6_BYTES - IPV4_BYTES, IPV6_BYTES) else null
    }

    private fun ranges(vararg entries: Pair<String, AddressClass>): List<Pair<AddressRange, AddressClass>> =
        entries.map { (cidr, addressClass) -> AddressRange.parse(cidr) to addressClass }
}

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16

/** `::ffff:0:0/96`: ten zero bytes, then two 0xFF bytes. */
private const val MAPPED_MARKER_START = 10
