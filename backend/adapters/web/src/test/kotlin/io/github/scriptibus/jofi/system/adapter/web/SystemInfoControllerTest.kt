// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.system.adapter.web

import io.github.scriptibus.jofi.system.application.GetSystemInfoUseCase
import io.github.scriptibus.jofi.system.application.port.BuildInfoPort
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

@WebMvcTest(SystemInfoController::class)
@Import(SystemInfoControllerTest.UseCaseConfig::class)
class SystemInfoControllerTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    @TestConfiguration
    class UseCaseConfig {
        @Bean
        fun buildInfoPort(): BuildInfoPort = mockk { every { applicationVersion() } returns "9.9.9-test" }

        @Bean
        fun getSystemInfoUseCase(buildInfoPort: BuildInfoPort): GetSystemInfoUseCase =
            GetSystemInfoUseCase(buildInfoPort)
    }

    @Test
    fun `GET system info returns name and version from the use case`() {
        mvc
            .get()
            .uri("/api/system/info")
            .accept(MediaType.APPLICATION_JSON)
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .isLenientlyEqualTo("""{"name":"Jofi","version":"9.9.9-test"}""")
    }
}
