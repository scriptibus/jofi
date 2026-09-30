// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /** The lookup did not answer within its time budget. */
    data object TimedOut : GuardDecision
}

/**
 * The SSRF check (threat model T1): resolves a host once and checks every address it resolves to.
 * If any address is not allowed the whole destination is blocked, because the client might pick
 * any of them. Callers must connect only to [GuardDecision.Allowed.addresses]; resolving again
 * would reopen the DNS rebinding window.
 *
 * The lookup runs on a virtual thread and is abandoned after [maxResolutionTime] (or the caller's
 * smaller budget): the system resolver cannot be interrupted, and a slow DNS server must not
 * stretch a fetch past its deadline. At most [MAX_ADDRESSES] addresses are returned, so a name
 * with many unreachable addresses cannot multiply connect timeouts.
 */
class DestinationGuard(
    private val allowlist: DestinationAllowlist = DestinationAllowlist.NONE,
    private val resolver: HostResolver = HostResolver.SYSTEM,
    private val classify: (InetAddress) -> AddressClass = AddressClassifier::classify,
    private val maxResolutionTime: Duration = DEFAULT_RESOLUTION_TIME,
) {
    fun check(
        destination: Destination,
        budget: Duration = maxResolutionTime,
    ): GuardDecision {
        val addresses = lookUp(destination.host, minOf(budget, maxResolutionTime))
        val refused = addresses?.map(classify)?.firstOrNull { !isPermitted(it, destination) }
        return when {
            addresses == null -> GuardDecision.TimedOut
            addresses.isEmpty() -> GuardDecision.Unresolvable
            refused != null -> GuardDecision.Blocked(refused)
            else -> GuardDecision.Allowed(addresses.take(MAX_ADDRESSES))
        }
    }

    /** The addresses (empty if unknown), or null if the lookup timed out. */
    private fun lookUp(
        host: String,
        timeout: Duration,
    ): List<InetAddress>? {
        val lookup = CompletableFuture.supplyAsync({ resolveOrEmpty(host) }, LOOKUPS)
        return try {
            lookup.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            lookup.cancel(true)
            null
        } catch (_: ExecutionException) {
            emptyList()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun resolveOrEmpty(host: String): List<InetAddress> =
        try {
            resolver.resolve(host)
        } catch (_: UnknownHostException) {
            emptyList()
        }

    private fun isPermitted(
        addressClass: AddressClass,
        destination: Destination,
    ): Boolean =
        addressClass == AddressClass.PUBLIC ||
            (addressClass.unlockedByAllowlist && allowlist.permitsInternal(destination))

    companion object {
        const val MAX_ADDRESSES = 4
        val DEFAULT_RESOLUTION_TIME: Duration = Duration.ofSeconds(5)
        private val LOOKUPS: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    }
}
