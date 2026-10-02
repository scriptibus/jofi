// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.POSTING_IMPORT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What the MCP tool contract tests share (#119): the app on a random port with a flagged phone number, an owner
 * with a session, and the SDK client with its helpers for results, tool errors and the changelog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class, McpToolContractSupport.FlaggedPhoneNumber::class)
@Suppress("VarCouldBeVal") // Spring injects the fields after construction.
open class McpToolContractSupport {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var dsl: DSLContext

    @Autowired
    private lateinit var context: ApplicationContext

    protected lateinit var owner: Session
    private val json = JsonMapper.builder().build()
    private val base get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun startWithAnOwner() {
        dsl.deleteFrom(TASK).execute()
        dsl.deleteFrom(CONTACT).execute()
        dsl.deleteFrom(POSTING_IMPORT).execute()
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        owner = Session().open().firstRun()
    }

    /** The changelog entries of one entity as (description, actor kind), oldest first. */
    protected fun changelog(
        type: String,
        id: String,
    ): List<Pair<String, String>> =
        dsl
            .select(CHANGELOG_ENTRY.DESCRIPTION, CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(type))
            .and(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .orderBy(CHANGELOG_ENTRY.OCCURRED_AT)
            .fetch()
            .map { it.value1() to it.value2() }

    protected fun McpSyncClient.call(
        name: String,
        arguments: Map<String, Any?>,
    ): JsonNode {
        val result = callTool(request(name, arguments))
        result.isError shouldBe false
        return json.readTree(text(result))
    }

    /** A call that fails as a tool error with [code]; the answer is returned for its problems. */
    protected fun McpSyncClient.failure(
        name: String,
        arguments: Map<String, Any?>,
        code: String,
    ): JsonNode {
        val result = callTool(request(name, arguments))
        result.isError shouldBe true
        val answer = json.readTree(text(result))
        answer["code"].asString() shouldBe code
        return answer
    }

    /** A call that may succeed or fail: whether it failed as a tool error, and the JSON answer. */
    protected fun McpSyncClient.outcome(
        name: String,
        arguments: Map<String, Any?>,
    ): Pair<Boolean, JsonNode> {
        val result = callTool(request(name, arguments))
        return (result.isError == true) to json.readTree(text(result))
    }

    /**
     * A call whose arguments break the tool's schema (wrong type, missing, out of range): the SDK refuses it as a
     * tool error before the tool runs, and nothing is stored (callers assert the changelog).
     */
    protected fun McpSyncClient.refused(
        name: String,
        arguments: Map<String, Any?>,
    ) {
        val result = callTool(request(name, arguments))
        result.isError shouldBe true
        // The tool's own errors are JSON with a code; the SDK's schema refusals are plain text.
        text(result) shouldNotContain "\"code\""
    }

    /** A call refused by the schema because the required property [key] is missing (and not for another reason). */
    protected fun McpSyncClient.refusedMissing(
        name: String,
        arguments: Map<String, Any?>,
        key: String,
    ) {
        val result = callTool(request(name, arguments))
        result.isError shouldBe true
        text(result) shouldContain "required property '$key' not found"
    }

    /** The problems of an error answer as `argument:problem`. */
    protected fun JsonNode.problems(): List<String> =
        this["problems"].values().map { "${it["argument"].asString()}:${it["problem"].asString()}" }

    protected fun JsonNode.untrusted(): JsonNode {
        this["trust"].asString() shouldBe Untrusted.TRUST
        this["notice"].asString() shouldBe Untrusted.NOTICE
        return this["content"]
    }

    private fun request(
        name: String,
        arguments: Map<String, Any?>,
    ) = McpSchema.CallToolRequest
        .builder(name)
        .arguments(arguments)
        .build()

    private fun text(result: McpSchema.CallToolResult) = (result.content().single() as McpSchema.TextContent).text()

    /** One client with its own cookies (session, `XSRF-TOKEN`), echoing the CSRF token like the SPA. */
    protected inner class Session {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private val http: HttpClient = HttpClient.newBuilder().cookieHandler(cookies).build()

        fun open(): Session = also { send("GET", "/api/auth/session").statusCode() shouldBe 200 }

        fun firstRun(): Session =
            also {
                val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
                send("POST", "/api/auth/first-run", body).statusCode() shouldBe 204
            }

        fun create(
            path: String,
            body: String,
        ): String {
            val response = send("POST", path, body)
            response.statusCode() shouldBe 201
            return json.readTree(response.body())["id"].asString()
        }

        fun send(
            method: String,
            path: String,
            body: String? = null,
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI.create(base + path))
                    .header("Accept", "application/json, text/event-stream")
            if (body != null) {
                request
                    .header(
                        "Content-Type",
                        "application/json",
                    ).method(method, HttpRequest.BodyPublishers.ofString(body))
            }
            if (method == "GET") request.GET()
            csrfToken()?.let { request.header(CSRF_HEADER, it) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        /** The SDK client over this session's cookies, sending the CSRF header on every request. */
        fun mcpClient(): McpSyncClient {
            val transport =
                HttpClientStreamableHttpTransport
                    .builder(base)
                    .endpoint("/mcp")
                    .clientBuilder(HttpClient.newBuilder().cookieHandler(cookies))
                    .httpRequestCustomizer {
                        builder,
                        _,
                        _,
                        _,
                        _,
                        ->
                        csrfToken()?.let { builder.header(CSRF_HEADER, it) }
                    }.build()
            return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
        }

        private fun csrfToken(): String? =
            cookies.cookieStore.cookies
                .firstOrNull { it.name == "XSRF-TOKEN" }
                ?.value
    }

    /** The knowledge context's future answer: one flagged phone number (M2). */
    @TestConfiguration(proxyBeanMethods = false)
    class FlaggedPhoneNumber {
        @Bean
        @Primary
        fun flaggedPhoneNumberSource(): AiVisibilityPort =
            object : AiVisibilityPort {
                override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult =
                    AiVisibilityResult.Known(NeverSendRules(emptyMap(), setOf(FlaggedValue(FLAGGED_PHONE))))
            }
    }

    protected companion object {
        const val MISSING = "00000000-0000-0000-0000-000000000000"
        const val PASSWORD = "correct horse battery staple"
        const val FLAGGED_PHONE = "0170 1234567"
        const val CSRF_HEADER = "X-XSRF-TOKEN"
        const val TIMEOUT_SECONDS = 10L
    }
}
