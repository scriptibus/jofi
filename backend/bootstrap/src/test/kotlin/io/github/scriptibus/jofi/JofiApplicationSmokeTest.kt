// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.application.port.ChangelogPort
import io.github.scriptibus.jofi.shared.domain.Actor
import io.github.scriptibus.jofi.shared.domain.ChangeSummary
import io.github.scriptibus.jofi.shared.domain.ChangelogEntry
import io.github.scriptibus.jofi.shared.domain.ChangelogLimit
import io.github.scriptibus.jofi.shared.domain.ChangelogResult
import io.github.scriptibus.jofi.shared.domain.EntityRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.assertj.MockMvcTester
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class JofiApplicationSmokeTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val changelog: ChangelogPort,
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
    @WithMockUser
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

    @Test
    fun `the changelog is wired to the database Flyway migrated at startup`() {
        val entry =
            ChangelogEntry(
                entity = EntityRef("system", "smoke-test"),
                actor = Actor.System("smoke-test"),
                occurredAt = Instant.parse("2026-09-30T00:00:00Z"),
                change = ChangeSummary("Smoke test"),
            )

        changelog.append(entry) shouldBe ChangelogResult.Success(Unit)
        changelog.listByEntity(entry.entity, ChangelogLimit(1)) shouldBe ChangelogResult.Success(listOf(entry))
    }
}
