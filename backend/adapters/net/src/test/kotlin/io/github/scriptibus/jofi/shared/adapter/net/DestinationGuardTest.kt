// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration

class DestinationGuardTest {
    private val ollama = Destination.of("ollama", 11434)

    @Test
    fun `a host whose addresses are all public is allowed with exactly those addresses`() {
        val guard = DestinationGuard(resolver = resolving("example.org" to listOf("93.184.215.14", "2606:2800:21f::1")))

        guard.check(Destination.of("example.org", 443)) shouldBe
            GuardDecision.Allowed(listOf(ip("93.184.215.14"), ip("2606:2800:21f::1")))
    }

    @Test
    fun `one internal address among public ones blocks the whole destination`() {
        val guard = DestinationGuard(resolver = resolving("mixed.example" to listOf("93.184.215.14", "10.0.0.5")))

        guard.check(Destination.of("mixed.example", 443)) shouldBe GuardDecision.Blocked(AddressClass.PRIVATE)
    }

    @Test
    fun `an allowlisted destination may resolve to a private address`() {
        val guard =
            DestinationGuard(
                allowlist = DestinationAllowlist.of(listOf(ollama)),
                resolver = resolving("ollama" to listOf("172.18.0.4")),
            )

        guard.check(ollama) shouldBe GuardDecision.Allowed(listOf(ip("172.18.0.4")))
    }

    @Test
    fun `the allowlist is per destination, so another port or host stays blocked`() {
        val guard =
            DestinationGuard(
                allowlist = DestinationAllowlist.of(listOf(ollama)),
                resolver = resolving("ollama" to listOf("172.18.0.4"), "db" to listOf("172.18.0.2")),
            )

        guard.check(Destination.of("ollama", 5432)) shouldBe GuardDecision.Blocked(AddressClass.PRIVATE)
        guard.check(Destination.of("db", 11434)) shouldBe GuardDecision.Blocked(AddressClass.PRIVATE)
    }

    @Test
    fun `link-local metadata stays blocked even for an allowlisted destination`() {
        val metadata = Destination.of("169.254.169.254", 80)
        val guard = DestinationGuard(allowlist = DestinationAllowlist.of(listOf(metadata)))

        guard.check(metadata) shouldBe GuardDecision.Blocked(AddressClass.LINK_LOCAL)
    }

    @Test
    fun `cloud metadata outside link-local stays blocked even for an allowlisted destination`() {
        val alibaba = Destination.of("100.100.100.200", 80)
        val azure = Destination.of("168.63.129.16", 80)
        val guard = DestinationGuard(allowlist = DestinationAllowlist.of(listOf(alibaba, azure)))

        guard.check(alibaba) shouldBe GuardDecision.Blocked(AddressClass.CLOUD_METADATA)
        guard.check(azure) shouldBe GuardDecision.Blocked(AddressClass.CLOUD_METADATA)
    }

    @Test
    fun `returns at most four addresses, after checking all of them`() {
        val many = (1..8).map { "93.184.215.$it" }
        val guard = DestinationGuard(resolver = resolving("many.example" to many))

        guard.check(Destination.of("many.example", 443)) shouldBe GuardDecision.Allowed(many.take(4).map(::ip))
        DestinationGuard(resolver = resolving("many.example" to many + "10.0.0.1"))
            .check(Destination.of("many.example", 443)) shouldBe GuardDecision.Blocked(AddressClass.PRIVATE)
    }

    @Test
    fun `a slow lookup is abandoned after its budget`() {
        val guard =
            DestinationGuard(
                resolver = {
                    Thread.sleep(5_000)
                    listOf(ip("93.184.215.14"))
                },
                maxResolutionTime = Duration.ofSeconds(2),
            )

        val started = System.nanoTime()
        guard.check(Destination.of("slow.example", 443), budget = Duration.ofMillis(200)) shouldBe
            GuardDecision.TimedOut
        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofMillis(1_000)
        shouldThrow<DnsTimeoutException> {
            GuardedDnsResolver(guard) { Duration.ofMillis(100) }.resolve("slow.example", 443)
        }
    }

    @Test
    fun `IP literals are classified without DNS, including bracketed IPv6 and trailing dots`() {
        val guard = DestinationGuard()

        guard.check(Destination.of("127.0.0.1", 80)) shouldBe GuardDecision.Blocked(AddressClass.LOOPBACK)
        guard.check(Destination.of("[::1]", 80)) shouldBe GuardDecision.Blocked(AddressClass.LOOPBACK)
        guard.check(Destination.of("[::ffff:169.254.169.254]", 80)) shouldBe
            GuardDecision.Blocked(AddressClass.LINK_LOCAL)
        guard.check(Destination.of("0.0.0.0", 80)) shouldBe GuardDecision.Blocked(AddressClass.UNSPECIFIED)
    }

    @Test
    fun `an unknown host or an empty answer is unresolvable`() {
        val guard = DestinationGuard(resolver = resolving("empty.example" to emptyList()))

        guard.check(Destination.of("empty.example", 443)) shouldBe GuardDecision.Unresolvable
        guard.check(Destination.of("missing.example", 443)) shouldBe GuardDecision.Unresolvable
    }

    @Test
    fun `the DNS resolver returns the checked addresses and resolves only once per connection`() {
        // DNS rebinding: the first answer is public, every later one points inside.
        val answers = ArrayDeque(listOf(listOf("93.184.215.14"), listOf("127.0.0.1")))
        var lookups = 0
        val resolver =
            GuardedDnsResolver(
                DestinationGuard(
                    resolver = {
                        lookups++
                        answers.removeFirst().map(::ip)
                    },
                ),
            )

        resolver.resolve("rebind.example", 443) shouldContainExactly listOf(InetSocketAddress(ip("93.184.215.14"), 443))
        lookups shouldBe 1
        // A second connection is a new, fully checked lookup, which now fails.
        shouldThrow<BlockedDestinationException> { resolver.resolve("rebind.example", 443) }.addressClass shouldBe
            AddressClass.LOOPBACK
    }

    @Test
    fun `the DNS resolver without a port never applies the allowlist`() {
        val loopback = Destination.of("127.0.0.1", 8080)
        val resolver = GuardedDnsResolver(DestinationGuard(DestinationAllowlist.of(listOf(loopback))))

        resolver.resolve("127.0.0.1", 8080).map { it.address } shouldContainExactly listOf(ip("127.0.0.1"))
        shouldThrow<BlockedDestinationException> { resolver.resolve("127.0.0.1") }
        shouldThrow<UnknownHostException> {
            GuardedDnsResolver(
                DestinationGuard(resolver = resolving()),
            ).resolve("x", 80)
        }
        resolver.resolveCanonicalHostname("example.org") shouldBe "example.org"
    }

    @Test
    fun `destinations normalize host and default ports`() {
        Destination.of(URI("http://Ollama.Local.:11434/v1")) shouldBe Destination("ollama.local", 11434)
        Destination.of(URI("https://example.org/")) shouldBe Destination("example.org", 443)
        Destination.of(URI("http://[::1]/")) shouldBe Destination("::1", 80)
        Destination.of(URI("ftp://example.org/")) shouldBe null
        Destination.of(URI("mailto:someone@example.org")) shouldBe null
    }

    private fun ip(literal: String): InetAddress = InetAddress.ofLiteral(literal)

    private fun resolving(vararg answers: Pair<String, List<String>>): HostResolver {
        val table = answers.toMap()
        return HostResolver { host -> table[host]?.map(::ip) ?: throw UnknownHostException(host) }
    }
}
