// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.InetAddress
import java.net.UnknownHostException

/** Looks up the addresses of a host; IP literals come back as themselves without a DNS query. */
fun interface HostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/** The guard's answer for one destination. */
sealed interface GuardDecision {
    /** Every address may be connected to; connect to exactly these (no second lookup). */
    data class Allowed(
        val addresses: List<InetAddress>,
    ) : GuardDecision

    /** At least one address is not allowed for this destination; carries its class, not the address. */
    data class Blocked(
        val addressClass: AddressClass,
    ) : GuardDecision

    data object Unresolvable : GuardDecision
}

/**
 * The SSRF check (threat model T1): resolves a host once and checks every address it resolves to.
 * If any address is not allowed the whole destination is blocked, because the client might pick
 * any of them. Callers must connect only to [GuardDecision.Allowed.addresses]; resolving again
 * would reopen the DNS rebinding window.
 */
class DestinationGuard(
    private val allowlist: DestinationAllowlist = DestinationAllowlist.NONE,
    private val resolver: HostResolver = HostResolver.SYSTEM,
    private val classify: (InetAddress) -> AddressClass = AddressClassifier::classify,
) {
    fun check(destination: Destination): GuardDecision {
        val addresses =
            try {
                resolver.resolve(destination.host)
            } catch (_: UnknownHostException) {
                emptyList()
            }
        val refused = addresses.map(classify).firstOrNull { !isPermitted(it, destination) }
        return when {
            addresses.isEmpty() -> GuardDecision.Unresolvable
            refused != null -> GuardDecision.Blocked(refused)
            else -> GuardDecision.Allowed(addresses)
        }
    }

    private fun isPermitted(
        addressClass: AddressClass,
        destination: Destination,
    ): Boolean =
        addressClass == AddressClass.PUBLIC ||
            (addressClass.unlockedByAllowlist && allowlist.permitsInternal(destination))
}
