// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.github.scriptibus.jofi.system.application.port.SetupTokenPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Behind a trusted TLS proxy (`JOFI_TRUSTED_PROXIES`) the real embedded Tomcat believes
 * `X-Forwarded-Proto` (both cookies get `Secure`) and `X-Forwarded-For` (the login backoff counts the
 * original client, not the proxy).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["JOFI_TRUSTED_PROXIES=127.0.0.1/32"],
)
@Import(PostgresTestConfiguration::class)
class TrustedProxyTest(
    @param:LocalServerPort private val port: Int,
    @param:Autowired private val setupToken: SetupTokenPort,
) {
    /** A client behind the proxy: keeps cookies itself (they are `Secure`, the hop is plain HTTP). */
    private inner class ProxiedClient(
        private val forwardedFor: String,
    ) {
        val cookies = mutableMapOf<String, String>()
        var setCookies: List<String> = emptyList()

        fun send(
            method: String,
            path: String,
            json: String? = null,
        ): Int {
            val builder =
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-For", forwardedFor)
                    .method(method, body(json))
            if (json != null) builder.header("Content-Type", "application/json")
            val cookieHeader = cookies.map { (name, value) -> "$name=$value" }.joinToString("; ")
            if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
            cookies["XSRF-TOKEN"]?.let { builder.header("X-XSRF-TOKEN", it) }
            val response =
                HttpClient.newHttpClient().use { it.send(builder.build(), HttpResponse.BodyHandlers.discarding()) }
            setCookies = response.headers().allValues("Set-Cookie")
            setCookies.forEach { header ->
                val (name, value) = header.substringBefore(';').split("=", limit = 2)
                if (value.isEmpty() || header.contains("Max-Age=0")) cookies.remove(name) else cookies[name] = value
            }
            return response.statusCode()
        }
    }

    @Test
    fun `forwarded protocol and client address are believed from the trusted proxy`() {
        setupToken.issue()
        val owner = ProxiedClient("203.0.113.10")
        owner.send("GET", "/api/auth/session") shouldBe 200
        owner.setCookies.single { it.startsWith("XSRF-TOKEN=") } shouldContain "Secure"

        val firstRun = """{"password":"$PASSWORD","setupToken":"${SetupTokens.read()}"}"""
        owner.send("POST", "/api/auth/first-run", firstRun) shouldBe 204
        owner.setCookies.single { it.startsWith("SESSION=") } shouldContain "Secure"
        owner.setCookies.last { it.startsWith("XSRF-TOKEN=") } shouldContain "Secure"

        val guesser = ProxiedClient("198.51.100.77").also { it.send("GET", "/api/auth/session") }
        val statuses = (1..7).map { guesser.send("POST", "/api/auth/login", """{"password":"guess $it"}""") }
        statuses.last() shouldBe 429

        // Same proxy, another original client: its own backoff counter.
        val other = ProxiedClient("198.51.100.78").also { it.send("GET", "/api/auth/session") }
        other.send("POST", "/api/auth/login", """{"password":"$PASSWORD"}""") shouldBe 204
    }

    private fun body(json: String?): HttpRequest.BodyPublisher =
        json?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()

    private companion object {
        const val PASSWORD = "correct horse battery staple"
    }
}
