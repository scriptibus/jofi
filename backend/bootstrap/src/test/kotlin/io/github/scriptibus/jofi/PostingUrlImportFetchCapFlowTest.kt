// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_AI
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.FAKE_POSTING
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.IMPORTS
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PASSWORD
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.PROVIDER
import io.github.scriptibus.jofi.PostingUrlImportFlowTest.Companion.addresses
import io.github.scriptibus.jofi.setup.application.port.ModelAssignmentPort
import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ModelAssignment
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_ASSIGNMENT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_PROVIDER_CONFIG
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.domain.ai.AiTask
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.github.tomakehurst.wiremock.client.WireMock.get as wireMockGet

/**
 * The cap on concurrent URL import fetches (#224) through the wired app, set to two: of three slow links submitted
 * at once two are fetched and one is turned away at once with `429 import-busy`; afterwards the permits are back.
 * Servers and the allowlisting configuration are those of [PostingUrlImportFlowTest].
 */
@SpringBootTest(properties = ["jofi.import.max-concurrent-fetches=2"])
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration::class, PostingUrlImportFlowTest.HttpTestConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostingUrlImportFetchCapFlowTest(
    @param:Autowired private val mvc: MockMvcTester,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val providers: ProviderConfigPort,
    @param:Autowired private val assignments: ModelAssignmentPort,
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

    private fun postingUrl(path: String): String = "http://127.0.0.1:${FAKE_POSTING.port()}$path"

    private fun servesSlowPosting(
        n: Int,
        delayMillis: Int,
    ) {
        FAKE_POSTING.stubFor(
            wireMockGet(urlEqualTo("/jobs/cap$n")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body><h1>Senior Kotlin Developer $n</h1></body></html>")
                    .withFixedDelay(delayMillis),
            ),
        )
    }

    private class Answer(
        val status: Int,
        val body: String,
        val tookMillis: Long,
    )

    @Test
    fun `the third of three slow links at once is turned away at once, and a new import works afterwards`() {
        val browser = owner()
        (0 until 3).forEach { servesSlowPosting(it, delayMillis = 3_000) }

        val answers = importAllAtOnce(browser, 3)

        answers.map { it.status } shouldContainExactlyInAnyOrder listOf(202, 202, 429)
        val refused = answers.indexOfFirst { it.status == 429 }
        json.readTree(answers[refused].body)["type"].asString() shouldBe "urn:jofi:problem:applications:import-busy"
        (answers[refused].tookMillis < 2_000) shouldBe true
        FAKE_POSTING.findAll(getRequestedFor(urlEqualTo("/jobs/cap$refused"))).size shouldBe 0
        dsl.fetchCount(POSTING_IMPORT) shouldBe 2

        servesSlowPosting(refused, delayMillis = 0)
        val retry = browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap$refused")}"}""")
        retry.response.status shouldBe 202
        dsl.fetchCount(POSTING_IMPORT) shouldBe 3
    }

    @Test
    fun `a link already pending takes no permit, so it is answered while the cap is used up`() {
        val browser = owner()
        servesSlowPosting(0, delayMillis = 0)
        val first = browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap0")}"}""")
        first.response.status shouldBe 202
        (1..2).forEach { servesSlowPosting(it, delayMillis = 3_000) }

        val pool = Executors.newFixedThreadPool(3)
        try {
            val slow =
                (1..2).map { n ->
                    pool.submit<Int> {
                        browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap$n")}"}""").response.status
                    }
                }
            awaitFetchesStarted("/jobs/cap1", "/jobs/cap2")

            val again = browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap0")}"}""")

            again.response.status shouldBe 202
            slow.map { it.get(60, TimeUnit.SECONDS) } shouldBe listOf(202, 202)
        } finally {
            pool.shutdownNow()
        }
        FAKE_POSTING.findAll(getRequestedFor(urlEqualTo("/jobs/cap0"))).size shouldBe 1
    }

    /** Both slow links reached the posting server, so both hold a permit (the stub answers only after 3 s). */
    private fun awaitFetchesStarted(vararg paths: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (paths.any { FAKE_POSTING.findAll(getRequestedFor(urlEqualTo(it))).isEmpty() }) {
            check(System.nanoTime() < deadline) { "the slow fetches never started" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `requests for one link whose first request was refused each get their own answer, never import in progress`() {
        val browser = owner()
        (0 until 2).forEach { servesSlowPosting(it, delayMillis = 3_000) }
        servesSlowPosting(5, delayMillis = 0)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow =
                (0 until 2).map { n ->
                    pool.submit<Int> {
                        browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap$n")}"}""").response.status
                    }
                }
            awaitFetchesStarted("/jobs/cap0", "/jobs/cap1")

            val waiters =
                concurrently(4) { browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap5")}"}""") }

            waiters.map { it.response.status } shouldBe List(4) { 429 }
            FAKE_POSTING.findAll(getRequestedFor(urlEqualTo("/jobs/cap5"))).size shouldBe 0
            slow.map { it.get(60, TimeUnit.SECONDS) } shouldBe listOf(202, 202)
        } finally {
            pool.shutdownNow()
        }
        browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap5")}"}""").response.status shouldBe 202
    }

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

    private fun importAllAtOnce(
        browser: Browser,
        count: Int,
    ): List<Answer> {
        val start = CyclicBarrier(count)
        val pool = Executors.newFixedThreadPool(count)
        try {
            return (0 until count)
                .map { n ->
                    pool.submit<Answer> {
                        start.await()
                        val began = System.nanoTime()
                        val result = browser.post("$IMPORTS/url", """{"url":"${postingUrl("/jobs/cap$n")}"}""")
                        Answer(
                            result.response.status,
                            result.response.contentAsString,
                            (System.nanoTime() - began) / 1_000_000,
                        )
                    }
                }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
