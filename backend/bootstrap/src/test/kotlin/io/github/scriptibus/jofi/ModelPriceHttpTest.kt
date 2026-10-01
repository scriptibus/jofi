// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.setup.application.port.ProviderConfigPort
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.AI_MODEL_PRICE_OVERRIDE
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.SPRING_SESSION
import io.github.scriptibus.jofi.shared.adapter.persistence.jooq.Tables.USER_ACCOUNT
import io.github.scriptibus.jofi.system.application.port.LoginThrottlePort
import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.github.scriptibus.jofi.system.domain.ThrottleKey
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The model price endpoints through the real embedded Tomcat (#142): model names with slashes, colons, `+`, `&`,
 * `#` and `%` travel in the body (`PUT`) and in the encoded query (`DELETE ?model=`) and come out unchanged, and
 * the `DELETE` needs a session and the CSRF token like the `PUT`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class)
class ModelPriceHttpTest(
    @param:LocalServerPort private val port: Int,
    @param:Autowired private val dsl: DSLContext,
    @param:Autowired private val context: ApplicationContext,
    @param:Autowired private val providers: ProviderConfigPort,
) {
    private val json = JsonMapper.builder().build()
    private val provider =
        ProviderConfig(
            ProviderId(UUID.randomUUID()),
            "HTTP test",
            ProviderKind.OPENAI_COMPATIBLE,
            null,
            URI("http://127.0.0.1:1/v1"),
        )
    private val path get() = "/api/setup/providers/${provider.id.value}/model-prices"

    private class Answer(
        val status: Int,
        val body: String,
    )

    private inner class Client {
        val cookies = mutableMapOf<String, String>()

        fun send(
            method: String,
            target: String,
            body: String? = null,
            csrf: Boolean = true,
        ): Answer {
            val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$target")).method(method, publisher)
            if (body != null) builder.header("Content-Type", "application/json")
            if (cookies.isNotEmpty()) builder.header("Cookie", cookies.map { (k, v) -> "$k=$v" }.joinToString("; "))
            if (csrf) cookies["XSRF-TOKEN"]?.let { builder.header("X-XSRF-TOKEN", it) }
            val response =
                HttpClient.newHttpClient().use {
                    it.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }
            response.headers().allValues("Set-Cookie").forEach { header ->
                val (name, value) = header.substringBefore(';').split("=", limit = 2)
                if (value.isEmpty() || header.contains("Max-Age=0")) cookies.remove(name) else cookies[name] = value
            }
            return Answer(response.statusCode(), response.body())
        }

        fun owner(): Client =
            also {
                send("GET", "/api/auth/session")
                val first = """{"password":"correct horse battery staple","setupToken":"${SetupTokens.read()}"}"""
                send("POST", "/api/auth/first-run", first).status shouldBe 204
            }
    }

    @BeforeEach
    fun startClean() {
        dsl.deleteFrom(SPRING_SESSION).execute()
        dsl.deleteFrom(USER_ACCOUNT).execute()
        dsl.deleteFrom(AI_MODEL_PRICE_OVERRIDE).execute()
        context.getBean(LoginThrottlePort::class.java).reset(ThrottleKey.Everyone)
        context.getBean(SetupTokenPort::class.java).issue()
        providers.save(provider)
    }

    private fun encoded(model: String) = URLEncoder.encode(model, StandardCharsets.UTF_8)

    private fun put(
        client: Client,
        model: String,
    ) = client.send(
        "PUT",
        path,
        """{"model":${json.writeValueAsString(
            model,
        )},"inputMicrosPerMillion":150000,"outputMicrosPerMillion":600000}""",
    )

    private fun listed(client: Client): List<String> {
        val nodes: Iterable<JsonNode> = json.readTree(client.send("GET", path).body)
        return nodes.map { it["model"].asString() }
    }

    @Test
    fun `a model name with slashes and reserved characters round-trips through PUT, GET and DELETE`() {
        val client = Client().owner()
        val names = listOf("meta-llama/llama-3.1-8b", "llama3.1:8b", "a+b&c#d%e=f ü", "100%/50%")

        names.forEach { put(client, it).status shouldBe 200 }
        listed(client).sorted() shouldBe names.sorted()

        names.forEach { client.send("DELETE", "$path?model=${encoded(it)}").status shouldBe 204 }
        listed(client) shouldBe emptyList()
    }

    @Test
    fun `the DELETE needs a session and the CSRF token, like the PUT`() {
        val stranger = Client()
        stranger.send("GET", "/api/auth/session")
        stranger.send("DELETE", "$path?model=x").status shouldBe 401

        val client = Client().owner()
        put(client, "kept").status shouldBe 200
        client.send("DELETE", "$path?model=kept", csrf = false).status shouldBe 403
        listed(client) shouldBe listOf("kept")
    }
}
