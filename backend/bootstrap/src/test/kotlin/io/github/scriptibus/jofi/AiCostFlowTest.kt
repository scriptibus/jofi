// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.setup.application.port.CostEntryPort
import io.github.scriptibus.jofi.setup.domain.CostEntry
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.Money
import io.github.scriptibus.jofi.setup.domain.MonthlyBudget
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MONTHLY_BUDGET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.TokenUsage
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The AI cost and budget API behind the real filter chain and database (#24): nothing is readable
 * without a session, the cap changes only with the CSRF token, lands in the changelog as the user, and
 * the report counts a call without a price instead of guessing its cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
class AiCostFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val costs: CostEntryPort,
) {
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun startWithoutUser() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        dsl.deleteFrom(AI_MONTHLY_BUDGET).execute()
        dsl.truncate(AI_COST_ENTRY).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "198.51.100.77").open().also {
            val body = """{"password":"correct horse battery staple","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    @Test
    fun `without a session nothing is readable, without the CSRF token the cap does not change`() {
        val stranger = Browser(mvc, "198.51.100.78").open()
        stranger.get("/api/setup/costs").response.status shouldBe 401
        stranger.get("/api/setup/costs/history").response.status shouldBe 401
        stranger.get("/api/setup/budget").response.status shouldBe 401

        val browser = owner()
        val forged = browser.exchange(HttpMethod.PUT, "/api/setup/budget", """{"capMicros":1}""", csrf = null)
        forged.response.status shouldBe 403
        forged.response.contentType shouldBe "application/problem+json"
        dsl.fetchCount(AI_MONTHLY_BUDGET) shouldBe 0
    }

    @Test
    fun `the user caps the month, sees the pause and removes the cap, each change in the changelog`() {
        val now = Instant.now()
        costs.append(entry(now, Money.usd(2_000_000)))
        costs.append(entry(now, null))
        val browser = owner()

        val capped = browser.put("/api/setup/budget", """{"capMicros":1000000}""")
        capped.response.status shouldBe 200
        json.readTree(capped.response.contentAsString)["state"].asString() shouldBe "REACHED"

        val summary = json.readTree(browser.get("/api/setup/costs").response.contentAsString)
        summary["total"]["calls"].asLong() shouldBe 2
        summary["total"]["knownCostMicros"].asLong() shouldBe 2_000_000
        summary["total"]["unknownCostCalls"].asLong() shouldBe 1
        summary["budget"]["pausedTasks"][0].asString() shouldBe "SCANNER_PRE_SCORING"

        browser.put("/api/setup/budget", """{"capMicros":null}""").response.status shouldBe 200
        dsl.fetchCount(AI_MONTHLY_BUDGET) shouldBe 0
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(MonthlyBudget.ENTITY_TYPE))
            .and(CHANGELOG_ENTRY.OCCURRED_AT.ge(now.minusSeconds(1).atOffset(ZoneOffset.UTC)))
            .fetch(CHANGELOG_ENTRY.ACTOR_KIND) shouldBe listOf("USER", "USER")
    }

    private fun entry(
        at: Instant,
        cost: Money?,
    ) = CostEntry(
        AiTask.SCANNER_PRE_SCORING,
        ProviderId(UUID.randomUUID()),
        ProviderKind.OPENAI_COMPATIBLE,
        ModelName("llama3.1"),
        TokenUsage(1_000, 100),
        cost,
        at,
    )
}
