// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.ANSWER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.COMPLETIONS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_AI
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_POSTING
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.IMPORTS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PASSWORD
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PROVIDER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.addresses
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.completion
import io.github.scriptibus.jofi.applications.adapter.jobs.PostingImportJobAdapter
import io.github.scriptibus.jofi.applications.domain.PostingImport
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.net.Destination
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.DestinationGuard
import io.github.scriptibus.jofi.shared.adapter.net.HostResolver
import io.github.scriptibus.jofi.shared.adapter.net.OutboundHttpAdapter
import io.github.scriptibus.jofi.shared.adapter.net.UserAgent
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.OutboundHttpPort
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.shared.domain.job.JobOutcome
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.github.tomakehurst.wiremock.client.WireMock.get as wireMockGet

/**
 * Concurrent URL and text imports (#97, #187 finding F6) through the wired app, with a connection pool of two: a
 * double submit fetches once and imports once, and slow fetches hold no database connection. Servers and the
 * allowlisting configuration are those of [PostingUrlImportFlowTest].
 */
@SpringBootTest(properties = ["spring.datasource.hikari.maximum-pool-size=2"])
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, PostingUrlImportFlowTest.HttpTestConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostingUrlImportConcurrencyFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val providers: ProviderConfigPort,
    @param:Autowired private val assignments: ModelAssignmentPort,
    @param:Autowired private val job: PostingImportJobAdapter,
) {
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun configureFakeProvider() {
        providers.save(PROVIDER)
        assignments.save(ModelAssignment(AiTask.EXTRACTION, PROVIDER.id, ModelName("fake-extraction")))
    }

    @AfterAll
    fun removeFakeProvider() {
        dsl.deleteFrom(AI_MODEL_ASSIGNMENT).where(AI_MODEL_ASSIGNMENT.PROVIDER_ID.eq(PROVIDER.id.value)).execute()
        dsl.deleteFrom(AI_PROVIDER_CONFIG).where(AI_PROVIDER_CONFIG.ID.eq(PROVIDER.id.value)).execute()
    }

    @BeforeEach
    fun startWithoutUserOrApplications() {
        FAKE_AI.resetAll()
        FAKE_POSTING.resetAll()
        dsl.deleteFrom(POSTING_IMPORT).execute()
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
    }

    private fun owner(): Browser =
        Browser(mvc, "198.51.100.${addresses.incrementAndGet()}").open().also {
            val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
            it.post("/api/auth/first-run", body).response.status shouldBe 204
        }

    private fun MvcTestResult.ok(status: Int = 200): JsonNode =
        also { response.status shouldBe status }.let { json.readTree(it.response.contentAsString) }

    private fun answers(content: String) {
        FAKE_AI.stubFor(post(COMPLETIONS).willReturn(completion(content)))
    }

    private fun postingUrl(path: String): String = "http://127.0.0.1:${FAKE_POSTING.port()}$path"

    private fun servesSlowPosting(n: Int) {
        FAKE_POSTING.stubFor(
            wireMockGet(urlEqualTo("/jobs/slow$n")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body><h1>Senior Kotlin Developer $n</h1></body></html>")
                    .withFixedDelay(4_000),
            ),
        )
    }

    @Test
    fun `slow imports hold no database connection, so a pool of two still serves other requests`() {
        val browser = owner()
        repeat(6, ::servesSlowPosting)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val imports =
                (0 until 6).map { n ->
                    pool.submit<JsonNode> {
                        browser
                            .post(
                                "$IMPORTS/url",
                                """{"url":"${postingUrl("/jobs/slow$n")}"}""",
                            ).ok(202)
                    }
                }
            Thread.sleep(1_000)

            val started = System.nanoTime()
            browser.get("/api/applications").ok()
            val tookMillis = (System.nanoTime() - started) / 1_000_000

            (tookMillis < 1_500) shouldBe true
            imports.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        dsl.fetchCount(POSTING_IMPORT) shouldBe 6
    }

    @Test
    fun `two requests for the same link at once fetch it once and answer with one import`() {
        val browser = owner()
        FAKE_POSTING.stubFor(
            wireMockGet(urlEqualTo("/jobs/slow")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body><h1>Senior Kotlin Developer</h1></body></html>")
                    .withFixedDelay(1_500),
            ),
        )
        answers(ANSWER)

        val answers =
            concurrently(2) { browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/slow")}"}""").ok(202) }

        answers.map { it["id"].asString() }.toSet().size shouldBe 1
        FAKE_POSTING.findAll(getRequestedFor(urlEqualTo("/jobs/slow"))).size shouldBe 1
        dsl.fetchCount(POSTING_IMPORT) shouldBe 1
        job.run(mapOf("import" to answers.first()["id"].asString())) shouldBe JobOutcome.Done
        dsl.fetchCount(APPLICATION) shouldBe 1
        FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).size shouldBe 1
    }

    @Test
    fun `two requests for the same pasted text at once answer with one import`() {
        val browser = owner()

        repeat(10) { round ->
            val body = json.writeValueAsString(mapOf("description" to "Kotlin Developer, round $round, ACME Robotics"))
            val answers = concurrently(2) { browser.post("$IMPORTS/text", body).ok(202) }
            answers.map { it["id"].asString() }.toSet().size shouldBe 1
        }
        dsl.fetchCount(POSTING_IMPORT) shouldBe 10
    }

    @Test
    fun `a stalled URL import is resumed by a resubmit, so the late job and the new one make one application`() {
        val browser = owner()
        FAKE_POSTING.stubFor(
            wireMockGet(urlEqualTo("/jobs/late")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body><h1>Senior Kotlin Developer</h1></body></html>"),
            ),
        )
        answers(ANSWER)
        val body = """{"url":"${postingUrl("/jobs/late")}"}"""
        val first = browser.post("$IMPORTS/url", body).ok(202)["id"].asString()
        stall(first)

        val again = browser.post("$IMPORTS/url", body).ok(202)

        again["id"].asString() shouldBe first
        runBothJobs(first)
        FAKE_POSTING.findAll(getRequestedFor(urlEqualTo("/jobs/late"))).size shouldBe 1
    }

    @Test
    fun `a stalled text import is resumed by a resubmit, so the late job and the new one make one application`() {
        val browser = owner()
        answers(ANSWER)
        val body = json.writeValueAsString(mapOf("description" to "Senior Kotlin Developer at ACME Robotics"))
        val first = browser.post("$IMPORTS/text", body).ok(202)["id"].asString()
        stall(first)

        val again = browser.post("$IMPORTS/text", body).ok(202)

        again["id"].asString() shouldBe first
        runBothJobs(first)
    }

    /** Makes the import look like one whose job has not run for [PostingImport.STALLED_AFTER]. */
    private fun stall(id: String) {
        val before = OffsetDateTime.now().minus(PostingImport.STALLED_AFTER).minusMinutes(1)
        dsl
            .update(POSTING_IMPORT)
            .set(POSTING_IMPORT.CREATED_AT, before)
            .set(POSTING_IMPORT.UPDATED_AT, before)
            .where(POSTING_IMPORT.ID.eq(UUID.fromString(id)))
            .execute()
    }

    /** The original job comes back late next to the one queued by the resubmit: both run, nothing doubles. */
    private fun runBothJobs(id: String) {
        repeat(2) { job.run(mapOf("import" to id)) shouldBe JobOutcome.Done }
        dsl.fetchCount(APPLICATION) shouldBe 1
        dsl.fetchCount(POSTING_IMPORT) shouldBe 1
        dsl.fetchValue(POSTING_IMPORT.STATUS) shouldBe "SUCCEEDED"
        FAKE_AI.findAll(postRequestedFor(urlEqualTo(COMPLETIONS))).size shouldBe 1
    }

    /** Runs [call] on [threads] threads released together, and answers what each returned. */
    private fun <T> concurrently(
        threads: Int,
        call: () -> T,
    ): List<T> {
        val start = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            return (1..threads)
                .map {
                    pool.submit<T> {
                        start.await()
                        call()
                    }
                }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
