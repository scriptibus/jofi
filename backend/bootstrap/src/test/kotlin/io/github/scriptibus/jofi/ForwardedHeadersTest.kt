// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * `X-Forwarded-*` is trusted only from the configured proxies (`JOFI_TRUSTED_PROXIES`), not from every
 * private address as Tomcat's default would: otherwise a LAN client could fake its address to dodge
 * the per-client login backoff, or fake HTTPS. Runs the real embedded Tomcat with a proxy list that
 * does not include the test client.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["JOFI_TRUSTED_PROXIES=192.0.2.1/32"],
)
@Import(PostgresTestConfiguration::class)
class ForwardedHeadersTest(
    @param:LocalServerPort private val port: Int,
    @param:Autowired private val environment: Environment,
) {
    @Test
    fun `forwarded headers from an untrusted peer are ignored`() {
        environment.getProperty("server.tomcat.remoteip.internal-proxies") shouldBe "192.0.2.1/32"
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/api/auth/session"))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", "203.0.113.7")
                .build()

        val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }

        response.statusCode() shouldBe 200
        val csrfCookie = response.headers().allValues("Set-Cookie").filter { it.startsWith("XSRF-TOKEN=") }
        csrfCookie.shouldNotBeEmpty()
        csrfCookie.first() shouldContain "SameSite=Lax"
        csrfCookie.first() shouldNotContain "Secure"
    }
}
