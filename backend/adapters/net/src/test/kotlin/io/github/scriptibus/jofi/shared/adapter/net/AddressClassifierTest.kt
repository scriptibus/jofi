// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.Inet6Address
import java.net.InetAddress

class AddressClassifierTest {
    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        // Public
        "1.1.1.1, PUBLIC",
        "8.8.8.8, PUBLIC",
        "93.184.215.14, PUBLIC",
        "100.63.255.255, PUBLIC",
        "100.128.0.0, PUBLIC",
        "172.15.255.255, PUBLIC",
        "172.32.0.0, PUBLIC",
        "192.167.255.255, PUBLIC",
        "192.169.0.0, PUBLIC",
        "223.255.255.255, PUBLIC",
        // "This network" and unspecified (0.0.0.0 reaches localhost on Linux)
        "0.0.0.0, UNSPECIFIED",
        "0.1.2.3, UNSPECIFIED",
        // Loopback, the whole /8
        "127.0.0.1, LOOPBACK",
        "127.255.255.254, LOOPBACK",
        // RFC 1918 edges
        "10.0.0.0, PRIVATE",
        "10.255.255.255, PRIVATE",
        "172.16.0.0, PRIVATE",
        "172.31.255.255, PRIVATE",
        "192.168.0.1, PRIVATE",
        "192.168.255.255, PRIVATE",
        // CGNAT / Tailscale
        "100.64.0.0, SHARED",
        "100.127.255.255, SHARED",
        // Link-local, including the cloud metadata services
        "169.254.169.254, LINK_LOCAL",
        "169.254.170.2, LINK_LOCAL",
        "169.254.0.0, LINK_LOCAL",
        // Special purpose
        "192.0.0.8, RESERVED",
        "192.0.2.1, RESERVED",
        "192.88.99.1, RESERVED",
        "198.18.0.1, RESERVED",
        "198.19.255.255, RESERVED",
        "198.51.100.7, RESERVED",
        "203.0.113.9, RESERVED",
        "224.0.0.1, MULTICAST",
        "239.255.255.250, MULTICAST",
        "240.0.0.1, RESERVED",
        "255.255.255.255, RESERVED",
    )
    fun `classifies IPv4 addresses`(
        address: String,
        expected: AddressClass,
    ) {
        AddressClassifier.classify(InetAddress.ofLiteral(address)) shouldBe expected
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
        "2606:4700:4700::1111, PUBLIC",
        "2a00:1450:4001:80b::200e, PUBLIC",
        "::, UNSPECIFIED",
        "::1, LOOPBACK",
        // IPv4-mapped and NAT64 addresses are classified by the embedded IPv4 address
        "::ffff:127.0.0.1, LOOPBACK",
        "::ffff:169.254.169.254, LINK_LOCAL",
        "::ffff:10.0.0.1, PRIVATE",
        "::ffff:8.8.8.8, PUBLIC",
        "64:ff9b::7f00:1, LOOPBACK",
        "64:ff9b::a9fe:a9fe, LINK_LOCAL",
        "64:ff9b::808:808, PUBLIC",
        "64:ff9b:1::1, RESERVED",
        // Deprecated IPv4-compatible and IPv4-translated forms
        "::127.0.0.1, RESERVED",
        "::ffff:0:7f00:1, RESERVED",
        "100::1, RESERVED",
        "2001::1, RESERVED",
        "2001:db8::1, RESERVED",
        "2002:7f00:1::1, RESERVED",
        "3fff::1, RESERVED",
        "fc00::1, UNIQUE_LOCAL",
        "fdff:ffff::1, UNIQUE_LOCAL",
        "fd00:ec2::254, CLOUD_METADATA",
        "fe80::1, LINK_LOCAL",
        "febf:ffff::1, LINK_LOCAL",
        "fec0::1, RESERVED",
        "ff02::1, MULTICAST",
        // Outside global unicast 2000::/3
        "4000::1, RESERVED",
        "1000::1, RESERVED",
    )
    fun `classifies IPv6 addresses`(
        address: String,
        expected: AddressClass,
    ) {
        AddressClassifier.classify(InetAddress.ofLiteral(address)) shouldBe expected
    }

    @ParameterizedTest(name = "{0} as a raw IPv6 address is {1}")
    @CsvSource("127.0.0.1, LOOPBACK", "169.254.169.254, LINK_LOCAL", "8.8.8.8, PUBLIC")
    fun `an IPv4-mapped address kept as Inet6Address is classified by its IPv4 part`(
        ipv4: String,
        expected: AddressClass,
    ) {
        // The JDK normally turns ::ffff:a.b.c.d into an Inet4Address; a resolver need not.
        val mapped = ByteArray(10) + byteArrayOf(-1, -1) + InetAddress.ofLiteral(ipv4).address
        val address = Inet6Address.getByAddress(null, mapped, -1)

        AddressClassifier.classify(address) shouldBe expected
    }

    @ParameterizedTest(name = "{0} may be unlocked: {1}")
    @CsvSource(
        "PUBLIC, false",
        "LOOPBACK, true",
        "PRIVATE, true",
        "SHARED, true",
        "UNIQUE_LOCAL, true",
        "LINK_LOCAL, false",
        "CLOUD_METADATA, false",
        "MULTICAST, false",
        "UNSPECIFIED, false",
        "RESERVED, false",
    )
    fun `only internal unicast ranges can be unlocked by an allowlist`(
        addressClass: AddressClass,
        unlocked: Boolean,
    ) {
        addressClass.unlockedByAllowlist shouldBe unlocked
    }
}
