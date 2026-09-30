// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.ThrottleKey
import java.net.Inet6Address
import java.net.InetAddress

/** Which client a login attempt counts against (ADR-0035). */
object ClientAddress {
    private const val IPV6_PREFIX_BYTES = 8

    /**
     * The throttle key of [remoteAddress]: IPv4 addresses as they are, IPv6 addresses by their /64
     * network, since one host usually owns a whole /64 and could otherwise use a fresh address per guess.
     */
    fun throttleKey(remoteAddress: String): ThrottleKey.Client =
        when (val address = parse(remoteAddress)) {
            null -> ThrottleKey.Client(remoteAddress)
            is Inet6Address -> ThrottleKey.Client("${network(address).hostAddress}/64")
            else -> ThrottleKey.Client(address.hostAddress)
        }

    private fun parse(remoteAddress: String): InetAddress? =
        try {
            InetAddress.ofLiteral(remoteAddress)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun network(address: Inet6Address): InetAddress =
        InetAddress.getByAddress(address.address.copyOf().also { it.fill(0, IPV6_PREFIX_BYTES, it.size) })
}
