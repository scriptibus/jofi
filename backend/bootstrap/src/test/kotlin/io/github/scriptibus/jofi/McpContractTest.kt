// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.mcp.Untrusted
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.shared.application.port.AiVisibilityPort
import io.github.scriptibus.jofi.shared.domain.ai.AiVisibilityResult
import io.github.scriptibus.jofi.shared.domain.ai.ContentSource
import io.github.scriptibus.jofi.shared.domain.ai.FlaggedValue
import io.github.scriptibus.jofi.shared.domain.ai.NeverSendRules
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * The MCP contract (ADR-0012, ADR-0053, spec §4.8b): the official MCP Java SDK client against the running app
 * (real Tomcat, filter chain and PostgreSQL) over Streamable HTTP at `/mcp`. No session means no MCP; with one,
 * the client lists the tools and calls each; results withhold flagged values and mark posting content
 * untrusted. Later tool PRs (#117, #118, #119) extend this suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class, McpContractTest.FlaggedPhoneNumber::class)
class McpContractTest(
    @param:LocalServerPort private val port: Int,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()
    private val base get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun startWithoutUserOrApplications() {
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        broken = false
    }

    @Test
    fun `when the flag source throws, the call answers privacy-filter-failed and leaks nothing`() {
        val owner = Session().open().firstRun()
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val id = owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$company"}""")

        owner.mcpClient().use { client ->
            client.initialize()
            broken = true

            val result = client.callTool(request("get_application", mapOf("id" to id)))

            result.isError shouldBe true
            text(result) shouldContain "\"code\":\"privacy-filter-failed\""
            text(result) shouldNotContain "Kotlin Engineer"
            text(result) shouldNotContain "1234567"
            text(result) shouldNotContain "flag store"
        }
    }

    @Test
    fun `without a session there is no MCP`() {
        val anonymous = Session().open()

        anonymous.send(HttpMethodRequest.GET, "/mcp").statusCode() shouldBe 401
        anonymous.send(HttpMethodRequest.POST, "/mcp", INITIALIZE).statusCode() shouldBe 401
        anonymous.send(HttpMethodRequest.POST, "/mcp", INITIALIZE, csrf = false).statusCode() shouldBe 403
        shouldThrowAny { anonymous.mcpClient().use { it.initialize() } }
    }

    @Test
    fun `a browser page of another origin gets 403, with or without the session`() {
        val anonymous = Session().open()
        val owner = Session().open().firstRun()
        val evil = "http://evil.example"

        anonymous.send(HttpMethodRequest.GET, "/mcp", origin = evil).statusCode() shouldBe 403
        owner.send(HttpMethodRequest.POST, "/mcp", INITIALIZE, origin = evil).statusCode() shouldBe 403
        owner.send(HttpMethodRequest.GET, "/api/auth/session", origin = evil).statusCode() shouldBe 200
    }

    @Test
    fun `the client lists exactly the tools there are, with the right read-only hint`() {
        Session().open().firstRun().mcpClient().use { client ->
            client.initialize().serverInfo().name() shouldBe "jofi"

            val tools = client.listTools().tools().associate { it.name() to it.annotations().readOnlyHint() }

            tools shouldBe TOOLS
        }
    }

    @Test
    fun `search and get return the application, posting content marked untrusted and flagged values withheld`() {
        val owner = Session().open().firstRun()
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        val id =
            owner.create(
                "/api/applications",
                """{"title":"Kotlin Engineer","companyId":"$company","location":"Berlin",
                   "portalNotes":"Recruiter asked to call $FLAGGED_PHONE"}""",
            )

        owner.mcpClient().use { client ->
            client.initialize()
            val search = client.call("search_applications", mapOf("text" to "Kotlin"))
            val application = client.call("get_application", mapOf("id" to id))

            search["total"].asInt() shouldBe 1
            search["applications"][0]["id"].asString() shouldBe id
            search["applications"][0]["posting"].untrusted()["title"].asString() shouldBe "Kotlin Engineer"
            application["id"].asString() shouldBe id
            application["posting"].untrusted()["location"].asString() shouldBe "Berlin"
            application["notes"].untrusted()["portalNotes"].asString() shouldBe "Recruiter asked to call [withheld]"
            application.toString() shouldNotContain "1234567"
        }
    }

    @Test
    fun `tool errors come back as tool results, not as protocol errors`() {
        Session().open().firstRun().mcpClient().use { client ->
            client.initialize()

            val result =
                client.callTool(
                    request("get_application", mapOf("id" to "00000000-0000-0000-0000-000000000000")),
                )

            result.isError shouldBe true
            text(result) shouldContain "\"code\":\"not-found\""
        }
    }

    private fun McpSyncClient.call(
        name: String,
        arguments: Map<String, Any>,
    ): JsonNode {
        val result = callTool(request(name, arguments))
        result.isError shouldBe false
        return json.readTree(text(result))
    }

    private fun request(
        name: String,
        arguments: Map<String, Any>,
    ) = McpSchema.CallToolRequest
        .builder(name)
        .arguments(arguments)
        .build()

    private fun text(result: McpSchema.CallToolResult) = (result.content().single() as McpSchema.TextContent).text()

    private fun JsonNode.untrusted(): JsonNode {
        this["trust"].asString() shouldBe Untrusted.TRUST
        this["notice"].asString() shouldBe Untrusted.NOTICE
        return this["content"]
    }

    private enum class HttpMethodRequest { GET, POST }

    /** One client with its own cookies (session, `XSRF-TOKEN`), echoing the CSRF token like the SPA. */
    private inner class Session {
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private val http: HttpClient = HttpClient.newBuilder().cookieHandler(cookies).build()

        fun open(): Session = also { send(HttpMethodRequest.GET, "/api/auth/session").statusCode() shouldBe 200 }

        fun firstRun(): Session =
            also {
                val body = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
                send(HttpMethodRequest.POST, "/api/auth/first-run", body).statusCode() shouldBe 204
            }

        fun create(
            path: String,
            body: String,
        ): String {
            val response = send(HttpMethodRequest.POST, path, body)
            response.statusCode() shouldBe 201
            return json.readTree(response.body())["id"].asString()
        }

        fun send(
            method: HttpMethodRequest,
            path: String,
            body: String? = null,
            csrf: Boolean = true,
            origin: String? = null,
        ): HttpResponse<String> {
            val request =
                HttpRequest
                    .newBuilder(URI.create(base + path))
                    .header("Accept", "application/json, text/event-stream")
            if (body != null) {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            }
            if (method == HttpMethodRequest.GET) request.GET()
            if (csrf) csrfToken()?.let { request.header(CSRF_HEADER, it) }
            origin?.let { request.header("Origin", it) }
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
                override fun rulesFor(sources: Set<ContentSource>): AiVisibilityResult {
                    check(!broken) { "flag store down while holding $FLAGGED_PHONE" }
                    return AiVisibilityResult.Known(NeverSendRules(emptyMap(), setOf(FlaggedValue(FLAGGED_PHONE))))
                }
            }
    }

    private companion object {
        /** Makes the flag source throw, as a broken knowledge store would (M2). */
        @Volatile
        var broken = false

        /**
         * The whole tool surface with its read-only hints, pinned by the one test that lists the tools: a PR that
         * adds a tool extends this map, and a write tool marked read only fails it.
         */
        val TOOLS =
            mapOf(
                "search_applications" to true,
                "get_application" to true,
                "search_companies" to true,
                "get_company" to true,
                "search_contacts" to true,
                "get_contact" to true,
                "create_company" to false,
                "update_company" to false,
                "create_contact" to false,
                "update_contact" to false,
                "set_application_contacts" to false,
                "create_application" to false,
                "update_application" to false,
                "log_interview" to false,
                "update_interview" to false,
                "list_interviews" to true,
                "get_interview" to true,
                "list_upcoming_interviews" to true,
                "start_text_import" to false,
                "get_import_status" to true,
                "list_tasks" to true,
                "list_task_suggestions" to true,
                "get_task" to true,
                "create_task" to false,
                "complete_task" to false,
                "accept_task_suggestion" to false,
                "list_done_tasks" to true,
                "reopen_task" to false,
                "delete_application" to false,
                "delete_interview" to false,
                "delete_company" to false,
                "delete_contact" to false,
                "delete_task" to false,
            )
        const val PASSWORD = "correct horse battery staple"
        const val FLAGGED_PHONE = "0170 1234567"
        const val CSRF_HEADER = "X-XSRF-TOKEN"
        const val TIMEOUT_SECONDS = 10L
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    }
}
