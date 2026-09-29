// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.application

import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.github.scriptibus.jofi.system.domain.SystemInfo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class GetSystemInfoUseCaseTest {
    private val buildInfo = mockk<BuildInfoPort>()
    private val useCase = GetSystemInfoUseCase(buildInfo)

    @Test
    fun `returns the product name with the version from the build`() {
        every { buildInfo.applicationVersion() } returns "1.4.0"

        useCase.execute() shouldBe SystemInfo(name = "Jofi", version = "1.4.0")

        verify(exactly = 1) { buildInfo.applicationVersion() }
    }
}
