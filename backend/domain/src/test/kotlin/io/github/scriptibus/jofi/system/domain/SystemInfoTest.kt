// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SystemInfoTest {
    @Test
    fun `of uses the product name`() {
        SystemInfo.of("1.2.3") shouldBe SystemInfo(name = "Jofi", version = "1.2.3")
    }

    @Test
    fun `rejects a blank name`() {
        shouldThrow<IllegalArgumentException> { SystemInfo(name = " ", version = "1.0.0") }
    }

    @Test
    fun `rejects a blank version`() {
        shouldThrow<IllegalArgumentException> { SystemInfo.of("") }
    }
}
