// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.shared.adapter.net.AiRequest
import io.github.scriptibus.jofi.shared.adapter.net.BlockedDestinationException
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SECRET
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.adapter.web.Confirmations
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provider setup API behind the real filter chain, database and AI transport (#23): only a
 * logged-in session with its CSRF token changes providers, keys never come back, and a newly
 * configured OpenAI-compatible base URL becomes reachable through the SSRF allowlist (ADR-0034) while
 * the metadata address stays blocked even when configured.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderSetupFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val aiTransport: GuardedAiTransport,
) {
    private val json = JsonMapper.builder().build()
    private val localAi = WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
    private val localModels = URI("http://127.0.0.1:${localAi.port()}/v1/models")

    @AfterAll
    fun stop() {
        localAi.stop()
    }

    @BeforeEach
    fun startWithoutUser() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        localAi.resetAll()
        localAi.stubFor(
            get("/v1/models").willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """{"object":"list","data":[{"id":"llama3.1","object":"model","created":0,"owned_by":"me"}]}""",
                    ),
            ),
        )
    }

    private fun owner(): Browser =
        Browser(mvc, "198.51.100.${addresses.incrementAndGet()}").open().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun Browser.createProvider(body: String): String {
        val created = post("/api/setup/providers", body)
        created.response.status shouldBe 201
        created.response.contentAsString shouldNotContain KEY
        return json.readTree(created.response.contentAsString)["id"].asString()
    }

    private fun reachable(uri: URI): Boolean =
        try {
            aiTransport.execute(AiRequest("GET", uri, emptyList(), null)).use { it.statusCode == 200 }
        } catch (_: BlockedDestinationException) {
            false
        }

    @Test
    fun `without a session nothing is readable, without the CSRF token nothing changes`() {
        val stranger = Browser(mvc, "198.51.100.250").open()
        stranger.get("/api/setup/providers").response.status shouldBe 401

        val browser = owner()
        val body = """{"kind":"OPENAI_COMPATIBLE","displayName":"Local","baseUrl":"http://127.0.0.1:1/v1"}"""
        browser.post("/api/setup/providers", body, csrf = null).response.status shouldBe 403
        browser.get("/api/setup/providers").response.status shouldBe 200
    }

    @Test
    fun `a configured local endpoint becomes reachable, is used with its key and is blocked again once removed`() {
        reachable(localModels) shouldBe false
        val browser = owner()

        val baseUrl = "http://127.0.0.1:${localAi.port()}/v1"
        val id =
            browser.createProvider(
                """{"kind":"OPENAI_COMPATIBLE","displayName":"Local","baseUrl":"$baseUrl","apiKey":"$KEY"}""",
            )

        reachable(localModels) shouldBe true
        val refreshed = browser.post("/api/setup/providers/$id/models/refresh")
        refreshed.response.status shouldBe 200
        json.readTree(refreshed.response.contentAsString)[0]["model"].asString() shouldBe "llama3.1"
        localAi.verify(getRequestedFor(urlEqualTo("/v1/models")).withHeader("Authorization", equalTo("Bearer $KEY")))
        storedText(id) shouldNotContain KEY
        browser.get("/api/setup/providers").response.contentAsString shouldNotContain KEY
        dsl
            .selectFrom(
                CHANGELOG_ENTRY,
            ).where(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .fetch(CHANGELOG_ENTRY.ACTOR_KIND)
            .toSet() shouldBe
            setOf("USER")

        val secretId = secretOf(id)
        val path = "/api/setup/providers/$id"
        val token = json.readTree(browser.delete(path).response.contentAsString)["confirmationToken"].asString()
        browser.delete(path, mapOf(Confirmations.HEADER to token)).response.status shouldBe 204
        reachable(localModels) shouldBe false
        dsl.fetchCount(SECRET, SECRET.ID.eq(secretId)) shouldBe 0
    }

    @Test
    fun `a changed base URL moves the allowlist, the old host and port are blocked, the new one reachable`() {
        val movedAi = WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
        try {
            val movedModels = URI("http://127.0.0.1:${movedAi.port()}/v1/models")
            movedAi.stubFor(get("/v1/models").willReturn(aResponse().withBody("{}")))
            val browser = owner()
            val first = "http://127.0.0.1:${localAi.port()}/v1"
            val id =
                browser.createProvider(
                    """{"kind":"OPENAI_COMPATIBLE","displayName":"Local","baseUrl":"$first","apiKey":"$KEY"}""",
                )
            reachable(localModels) shouldBe true
            reachable(movedModels) shouldBe false

            val moved = """{"displayName":"Local","baseUrl":"http://127.0.0.1:${movedAi.port()}/v1","apiKey":"$KEY"}"""
            browser.put("/api/setup/providers/$id", moved).response.status shouldBe 200

            reachable(localModels) shouldBe false
            reachable(movedModels) shouldBe true
            dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(UUID.fromString(id))).execute()
        } finally {
            movedAi.stop()
        }
    }

    private fun secretOf(id: String): UUID =
        checkNotNull(
            dsl
                .select(AI_PROVIDER_CONFIG.API_KEY_SECRET_ID)
                .from(AI_PROVIDER_CONFIG)
                .where(AI_PROVIDER_CONFIG.ID.eq(UUID.fromString(id)))
                .fetchOne(AI_PROVIDER_CONFIG.API_KEY_SECRET_ID),
        )

    @Test
    fun `the metadata address stays blocked even as a configured base URL`() {
        val browser = owner()
        val id =
            browser.createProvider(
                """{"kind":"OPENAI_COMPATIBLE","displayName":"Metadata","baseUrl":"http://169.254.169.254/v1"}""",
            )

        reachable(URI("http://169.254.169.254/latest/meta-data/")) shouldBe false
        val refreshed = browser.post("/api/setup/providers/$id/models/refresh")
        refreshed.response.status shouldBe 502
        json.readTree(refreshed.response.contentAsString)["type"].asString() shouldBe
            "urn:jofi:problem:setup:provider-unreachable"
        shouldThrow<BlockedDestinationException> {
            aiTransport.execute(AiRequest("GET", URI("http://169.254.169.254/v1/models"), emptyList(), null))
        }
        dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(UUID.fromString(id))).execute()
    }

    /** The provider row and every secret, as text: the key must be in neither. */
    private fun storedText(id: String): String {
        val provider =
            dsl
                .selectFrom(
                    AI_PROVIDER_CONFIG,
                ).where(AI_PROVIDER_CONFIG.ID.eq(UUID.fromString(id)))
                .fetchOne()
        val secrets =
            dsl
                .select(SECRET.CIPHERTEXT)
                .from(SECRET)
                .fetch(SECRET.CIPHERTEXT)
                .map { String(it) }
        return provider.toString() + secrets.joinToString()
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val KEY = "sk-local-0123456789abcdef"
        val addresses = AtomicInteger()
    }
}
