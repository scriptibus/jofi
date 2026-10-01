// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.APPLICATION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CHANGELOG_ENTRY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.COMPANY
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.CONTACT
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.TASK
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The MCP delete tools and their server-enforced two-step confirmation (ADR-0039, #117), with the official MCP
 * Java SDK client against the running app: without a client that can ask its user nothing is deleted; the user's
 * yes (MCP elicitation) deletes, with the AI as actor in the changelog; a no, a change of the effect between the
 * steps, a missing target or a company with applications delete nothing. The model never sees the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class McpDeleteContractTest(
    @param:LocalServerPort private val port: Int,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
) {
    private val json = JsonMapper.builder().build()
    private val base get() = "http://127.0.0.1:$port"
    private lateinit var owner: Session
    private lateinit var keptCompany: String

    @BeforeEach
    fun startWithAnOwner() {
        dsl.deleteFrom(TASK).execute()
        dsl.deleteFrom(CONTACT).execute()
        dsl.deleteFrom(APPLICATION).execute()
        dsl.deleteFrom(COMPANY).execute()
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        owner = Session().open().firstRun()
    }

    /** One entity a delete tool targets: its tool, its arguments, where REST finds it, and its changelog type. */
    private class Target(
        val tool: String,
        val arguments: Map<String, Any>,
        val path: String,
        val entityType: String,
        val id: String,
        val effectName: String,
    )

    private fun targets(): List<Target> {
        val kept = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        keptCompany = kept
        val application = owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$kept"}""")
        val interview = owner.create("/api/applications/$application/interviews", INTERVIEW)
        val company = owner.create("/api/companies", """{"name":"Doomed GmbH"}""")
        val contact = owner.create("/api/contacts", """{"name":"Erika Mustermann"}""")
        val task = owner.create("/api/tasks", TASK_BODY)
        return listOf(
            // The interview first: deleting its application takes it along.
            Target(
                "delete_interview",
                mapOf("applicationId" to application, "id" to interview),
                "/api/applications/$application/interviews/$interview",
                "interview",
                interview,
                "PHONE_SCREEN",
            ),
            simple("delete_application", "application", "applications", application, "Kotlin Engineer"),
            simple("delete_company", "company", "companies", company, "Doomed GmbH"),
            simple("delete_contact", "contact", "contacts", contact, "Erika Mustermann"),
            simple("delete_task", "task", "tasks", task, "Call Erika back"),
        )
    }

    private fun simple(
        tool: String,
        type: String,
        collection: String,
        id: String,
        effectName: String,
    ) = Target(tool, mapOf("id" to id), "/api/$collection/$id", type, id, effectName)

    private fun Target.exists() = owner.send("GET", path).statusCode() == 200

    private fun Target.deletions(): List<String> =
        dsl
            .select(CHANGELOG_ENTRY.ACTOR_KIND)
            .from(CHANGELOG_ENTRY)
            .where(CHANGELOG_ENTRY.ENTITY_TYPE.eq(entityType))
            .and(CHANGELOG_ENTRY.ENTITY_ID.eq(id))
            .and(CHANGELOG_ENTRY.DESCRIPTION.startsWith("Deleted"))
            .fetch()
            .map { it.value1() }

    @Test
    fun `the delete tools are listed as changing tools`() {
        owner.mcpClient(answer = null).use { client ->
            client.initialize()

            val tools = client.listTools().tools().filter { it.name().startsWith("delete_") }

            tools.map { it.name() }.toSet() shouldBe
                setOf("delete_application", "delete_interview", "delete_company", "delete_contact", "delete_task")
            tools.all { it.annotations().readOnlyHint() == false } shouldBe true
        }
    }

    @Test
    fun `without a client that can ask its user, no delete tool deletes or records anything`() {
        val all = targets()
        owner.mcpClient(answer = null).use { client ->
            client.initialize()
            all.forEach { target ->
                val result = client.callTool(request(target.tool, target.arguments))

                result.isError shouldBe true
                text(result) shouldContain "\"code\":\"confirmation-unavailable\""
                text(result) shouldNotContain "oken"
                target.exists() shouldBe true
                target.deletions().shouldBeEmpty()
            }
        }
    }

    @Test
    fun `when the user confirms, each delete tool deletes with the AI as actor and never shows the token`() {
        val all = targets()
        val questions = mutableListOf<String>()
        owner
            .mcpClient(answer = {
                questions += it
                accept()
            })
            .use { client ->
                client.initialize()
                all.forEach { target ->
                    val result = client.callTool(request(target.tool, target.arguments))

                    result.isError shouldBe false
                    text(result) shouldContain "\"status\":\"deleted\""
                    text(result) shouldContain target.id
                    text(result) shouldNotContain "oken"
                    target.exists() shouldBe false
                    target.deletions() shouldContainExactly listOf("AI")
                }
            }
        questions.size shouldBe all.size
        all.forEach { target -> questions.any { it.contains(target.effectName) } shouldBe true }
    }

    @Test
    fun `a declined or cancelled confirmation deletes nothing`() {
        val all = targets()
        listOf(
            McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null),
            McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, null),
            McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, mapOf("confirm" to false)),
            McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, emptyMap()),
        ).forEach { answer ->
            owner.mcpClient(answer = { answer }).use { client ->
                client.initialize()
                all.forEach { target ->
                    val result = client.callTool(request(target.tool, target.arguments))

                    result.isError shouldBe false
                    text(result) shouldContain "\"status\":\"declined\""
                    target.exists() shouldBe true
                    target.deletions().shouldBeEmpty()
                }
            }
        }
    }

    @Test
    fun `a change of what the delete affects between the steps voids the confirmation`() {
        val application = targets().first { it.tool == "delete_application" }
        val renamed =
            """{"details":{"title":"Renamed while the user was reading","companyId":"$keptCompany"},"basedOnVersion":0}"""
        owner
            .mcpClient(answer = {
                owner.send("PUT", application.path, renamed).statusCode() shouldBe 200
                accept()
            })
            .use { client ->
                client.initialize()

                val result = client.callTool(request(application.tool, application.arguments))

                result.isError shouldBe true
                text(result) shouldContain "\"code\":\"confirmation-invalid\""
                application.exists() shouldBe true
                application.deletions().shouldBeEmpty()
            }
    }

    @Test
    fun `a missing target is not-found without asking the user`() {
        val asked = mutableListOf<String>()
        val missing = UUID.randomUUID().toString()
        owner
            .mcpClient(answer = {
                asked += it
                accept()
            })
            .use { client ->
                client.initialize()

                listOf("delete_application", "delete_company", "delete_contact", "delete_task").forEach { tool ->
                    val result = client.callTool(request(tool, mapOf("id" to missing)))

                    result.isError shouldBe true
                    text(result) shouldContain "\"code\":\"not-found\""
                }
            }
        asked.shouldBeEmpty()
    }

    @Test
    fun `a company with applications is refused before the user is asked`() {
        val company = owner.create("/api/companies", """{"name":"ACME GmbH"}""")
        owner.create("/api/applications", """{"title":"Kotlin Engineer","companyId":"$company"}""")
        val asked = mutableListOf<String>()
        owner
            .mcpClient(answer = {
                asked += it
                accept()
            })
            .use { client ->
                client.initialize()

                val result = client.callTool(request("delete_company", mapOf("id" to company)))

                result.isError shouldBe true
                text(result) shouldContain "\"code\":\"has-applications\""
                asked.shouldBeEmpty()
            }
    }

    @Test
    fun `a malformed id is an invalid argument and the user is not asked`() {
        owner.mcpClient(answer = { error("must not ask") }).use { client ->
            client.initialize()

            val result = client.callTool(request("delete_task", mapOf("id" to "not-a-uuid")))

            result.isError shouldBe true
            text(result) shouldContain "\"code\":\"invalid-arguments\""
        }
    }

    private fun accept() = McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, mapOf("confirm" to true))

    private fun request(
        name: String,
        arguments: Map<String, Any>,
    ) = McpSchema.CallToolRequest
        .builder(name)
        .arguments(arguments)
        .build()

    private fun text(result: McpSchema.CallToolResult) = (result.content().single() as McpSchema.TextContent).text()

    /** One client with its own cookies (session, `XSRF-TOKEN`), echoing the CSRF token like the SPA. */
    private inner class Session {
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
                    .header("Content-Type", "application/json")
                    .method(
                        method,
                        body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
                    )
            csrfToken()?.let { request.header(CSRF_HEADER, it) }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        /**
         * The SDK client over this session. With an [answer] it declares the elicitation capability and lets that
         * function answer the user's question; without, it is a client that cannot ask its user.
         */
        fun mcpClient(answer: ((String) -> McpSchema.ElicitResult)?): McpSyncClient {
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
            val client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            if (answer != null) {
                client
                    .capabilities(
                        McpSchema.ClientCapabilities
                            .builder()
                            .elicitation()
                            .build(),
                    ).elicitation { answer(it.message()) }
            }
            return client.build()
        }

        private fun csrfToken(): String? =
            cookies.cookieStore.cookies
                .firstOrNull { it.name == "XSRF-TOKEN" }
                ?.value
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        const val CSRF_HEADER = "X-XSRF-TOKEN"
        const val TIMEOUT_SECONDS = 10L
        const val INTERVIEW =
            """{"type":"PHONE_SCREEN","localStart":"2026-10-12T09:00","timeZone":"Europe/Berlin","participantIds":[]}"""
        const val TASK_BODY =
            """{"title":"Call Erika back","timing":{"timeZone":"Europe/Berlin","bucket":"THIS_WEEK"}}"""
    }
}
