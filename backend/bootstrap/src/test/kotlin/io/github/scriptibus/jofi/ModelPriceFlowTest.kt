// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ModelCapabilityPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.Capability
import io.github.scriptibus.jofi.setup.domain.CapabilitySource
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelCapabilities
import io.github.scriptibus.jofi.setup.domain.ModelCapabilityProfile
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_COST_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_PRICE_OVERRIDE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MONTHLY_BUDGET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.LlmPort
import io.github.scriptibus.jofi.shared.domain.ai.AiResult
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.ai.LlmMessage
import io.github.scriptibus.jofi.shared.domain.ai.LlmRequest
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * The user's model prices through the wired app (#142, ADR-0055): REST behind the real filter chain,
 * the real gateway against a wire-level fake provider, the meter in PostgreSQL and the cost report. A call
 * on a model with a price is priced and counts toward the cap, a price of zero is a known cost, and a price
 * never re-prices what was metered before it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelPriceFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val llm: LlmPort,
    @param:Autowired private val providers: ProviderConfigPort,
    @param:Autowired private val assignments: ModelAssignmentPort,
    @param:Autowired private val capabilities: ModelCapabilityPort,
) {
    private val json = JsonMapper.builder().build()
    private val prices get() = "/api/setup/providers/${PROVIDER.id.value}/model-prices"

    @BeforeAll
    fun configureFakeProvider() {
        providers.save(PROVIDER)
        listOf(AiTask.CHAT, AiTask.SCANNER_PRE_SCORING).forEach { task ->
            assignments.save(ModelAssignment(task, PROVIDER.id, MODEL))
        }
        capabilities.save(
            ModelCapabilityProfile(
                PROVIDER.id,
                MODEL,
                ModelCapabilities(setOf(Capability.ToolUse, Capability.Streaming)),
                CapabilitySource.USER,
                Instant.now(),
            ),
        )
        FAKE_AI.stubFor(
            post(
                "/v1/chat/completions",
            ).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(ANSWER)),
        )
    }

    @AfterAll
    fun stop() {
        FAKE_AI.stop()
    }

    @BeforeEach
    fun startClean() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        dsl.deleteFrom(AI_MONTHLY_BUDGET).execute()
        dsl.deleteFrom(AI_MODEL_PRICE_OVERRIDE).execute()
        dsl.truncate(AI_COST_ENTRY).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "198.51.100.90").open().also {
            val body = """{"password":"correct horse battery staple","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun call(task: AiTask = AiTask.CHAT) {
        llm.complete(LlmRequest(task, listOf(LlmMessage.User("Hallo")))).shouldBeInstanceOf<AiResult.Success<*>>()
    }

    private fun report(browser: Browser): JsonNode =
        json.readTree(browser.get("/api/setup/costs").response.contentAsString)

    private fun setPrice(
        browser: Browser,
        input: Long,
        output: Long,
    ) = browser
        .put(prices, """{"model":"${MODEL.value}","inputMicrosPerMillion":$input,"outputMicrosPerMillion":$output}""")

    @Test
    fun `without a session or the CSRF token no price changes`() {
        Browser(
            mvc,
            "198.51.100.91",
        ).open()
            .put(prices, """{"model":"m","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""")
            .response.status shouldBe 401

        val browser = owner()
        val forged =
            browser.exchange(
                HttpMethod.PUT,
                prices,
                """{"model":"m","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""",
                csrf = null,
            )
        forged.response.status shouldBe 403
        dsl.fetchCount(AI_MODEL_PRICE_OVERRIDE) shouldBe 0
    }

    @Test
    fun `a priced model is priced from then on, shows in the report and keeps earlier calls unknown`() {
        val browser = owner()
        call()

        setPrice(browser, 150_000, 600_000).response.status shouldBe 200
        call()

        // 12 input tokens at 0.15 USD and 3 output tokens at 0.60 USD per million: 3.6 micros, rounded up.
        val total = report(browser)["total"]
        total["calls"].asLong() shouldBe 2
        total["knownCostMicros"].asLong() shouldBe 4
        total["unknownCostCalls"].asLong() shouldBe 1
        val listed = json.readTree(browser.get(prices).response.contentAsString)
        listed[0]["model"].asString() shouldBe MODEL.value
        listed[0]["inputMicrosPerMillion"].asLong() shouldBe 150_000
    }

    @Test
    fun `a price of zero is a known cost and a removed price is unknown again`() {
        val browser = owner()

        setPrice(browser, 0, 0).response.status shouldBe 200
        call()
        report(browser)["total"]["unknownCostCalls"].asLong() shouldBe 0

        browser.delete("$prices?model=${MODEL.value}").response.status shouldBe 204
        call()
        report(browser)["total"]["unknownCostCalls"].asLong() shouldBe 1
        browser.delete("$prices?model=${MODEL.value}").response.status shouldBe 204
    }

    @Test
    fun `priced calls count toward the monthly cap`() {
        val browser = owner()
        browser.put("/api/setup/budget", """{"capMicros":6}""").response.status shouldBe 200
        setPrice(browser, 150_000, 600_000).response.status shouldBe 200

        call(AiTask.SCANNER_PRE_SCORING)
        call(AiTask.SCANNER_PRE_SCORING)

        llm.complete(LlmRequest(AiTask.SCANNER_PRE_SCORING, listOf(LlmMessage.User("Hallo")))) shouldBe
            AiResult.BudgetExceeded(AiTask.SCANNER_PRE_SCORING)
        report(browser)["budget"]["pausedTasks"][0].asString() shouldBe "SCANNER_PRE_SCORING"
    }

    @Test
    fun `every price change is a changelog entry of the user on the provider`() {
        val browser = owner()
        val entries =
            dsl
                .select(CHANGELOG_ENTRY.ACTOR_KIND, CHANGELOG_ENTRY.DESCRIPTION)
                .from(CHANGELOG_ENTRY)
                .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(ProviderId.ENTITY_TYPE))
                .and(CHANGELOG_ENTRY.ENTITY_ID.eq(PROVIDER.id.value.toString()))
                .orderBy(CHANGELOG_ENTRY.ID)
        val earlier = entries.fetch().size

        setPrice(browser, 1, 2)
        setPrice(browser, 1, 2)
        browser.delete("$prices?model=${MODEL.value}")

        entries.fetch().drop(earlier).map { it.value1() to it.value2() } shouldBe
            listOf("USER" to "Set the price of model fake-model", "USER" to "Removed the price of model fake-model")
    }

    @Test
    fun `a cloud provider takes no price`() {
        val browser = owner()
        val cloud =
            browser.post(
                "/api/setup/providers",
                """{"kind":"OPENAI","displayName":"OpenAI","apiKey":"sk-test-123"}""",
            )
        cloud.response.status shouldBe 201
        val id = json.readTree(cloud.response.contentAsString)["id"].asString()

        val refused =
            browser.put(
                "/api/setup/providers/$id/model-prices",
                """{"model":"gpt-5-mini","inputMicrosPerMillion":1,"outputMicrosPerMillion":1}""",
            )

        refused.response.status shouldBe 409
        json.readTree(refused.response.contentAsString)["type"].asString() shouldBe
            "urn:jofi:problem:setup:price-not-allowed"
    }

    private companion object {
        val MODEL = ModelName("fake-model")
        val FAKE_AI: WireMockServer =
            WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
        val PROVIDER =
            ProviderConfig(
                ProviderId(UUID.randomUUID()),
                "Fake AI",
                ProviderKind.OPENAI_COMPATIBLE,
                null,
                URI("http://127.0.0.1:${FAKE_AI.port()}/v1"),
            )

        const val ANSWER =
            """{"id":"c","object":"chat.completion","created":1,"model":"fake-model",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Erledigt"},""" +
                """"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}"""
    }
}
