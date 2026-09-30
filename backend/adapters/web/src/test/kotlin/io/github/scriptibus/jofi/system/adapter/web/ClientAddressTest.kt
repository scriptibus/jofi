// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClientAddressTest {
    @Test
    fun `IPv4 clients are counted by address`() {
        ClientAddress.throttleKey("192.0.2.7") shouldBe ThrottleKey.Client("192.0.2.7")
    }

    @Test
    fun `IPv6 clients are counted by their 64-bit network`() {
        val first = ClientAddress.throttleKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")

        first shouldBe ClientAddress.throttleKey("2001:db8:1:2::1")
        first shouldBe ThrottleKey.Client("2001:db8:1:2:0:0:0:0/64")
        (first == ClientAddress.throttleKey("2001:db8:1:3::1")) shouldBe false
    }

    @Test
    fun `anything else is kept as it is`() {
        ClientAddress.throttleKey("unix-socket") shouldBe ThrottleKey.Client("unix-socket")
    }
}
