// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.assertj.MockMvcTester

@SpringBootTest
@AutoConfigureMockMvc
class JofiApplicationSmokeTest(
    @param:Autowired private val mvc: MockMvcTester,
) {
    @Test
    fun `context starts and reports healthy`() {
        mvc
            .get()
            .uri("/actuator/health")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.status")
            .isEqualTo("UP")
    }

    @Test
    fun `system info is wired end to end`() {
        mvc
            .get()
            .uri("/api/system/info")
            .assertThat()
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.name")
            .isEqualTo("Jofi")
    }
}
