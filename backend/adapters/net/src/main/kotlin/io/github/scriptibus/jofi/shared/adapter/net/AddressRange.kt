// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.InetAddress

/**
 * A CIDR block such as `10.0.0.0/8` or `fc00::/7`, compared on raw address bytes. Only IP literals
 * are parsed (no DNS lookup): [InetAddress.ofLiteral] rejects host names.
 */
internal class AddressRange private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
) {
    /** True for an address of the same family whose first [prefixLength] bits match the network. */
    fun contains(address: ByteArray): Boolean =
        address.size == network.size && (0 until prefixLength).all { bit -> bitAt(address, bit) == bitAt(network, bit) }

    private fun bitAt(
        bytes: ByteArray,
        bit: Int,
    ): Int = (bytes[bit / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)) and 1

    companion object {
        fun parse(cidr: String): AddressRange {
            val (literal, prefix) = cidr.split('/').also { require(it.size == 2) { "Not a CIDR block: $cidr" } }
            val bytes = InetAddress.ofLiteral(literal).address
            val prefixLength = prefix.toInt()
            require(prefixLength in 0..bytes.size * Byte.SIZE_BITS) { "Invalid prefix length: $cidr" }
            return AddressRange(bytes, prefixLength)
        }
    }
}
