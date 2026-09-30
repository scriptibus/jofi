// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi

import jakarta.servlet.http.Cookie
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.Base64

/**
 * A minimal browser for MockMvc: keeps the cookies the app sets (session, `XSRF-TOKEN`), sends them
 * back and echoes the CSRF cookie in `X-XSRF-TOKEN` the way the SPA does, from one client address.
 */
class Browser(
    private val mvc: MockMvcTester,
    private val address: String,
    private val https: Boolean = false,
) {
    val cookies = mutableMapOf<String, String>()

    /** The raw `Set-Cookie` headers of the last response. */
    var lastSetCookies: List<String> = emptyList()
        private set

    /** The session id behind the `SESSION` cookie (Spring Session encodes it in Base64). */
    val sessionId: String?
        get() = cookies[SESSION_COOKIE]?.let { String(Base64.getDecoder().decode(it)) }

    fun get(path: String): MvcTestResult = exchange(HttpMethod.GET, path)

    fun post(
        path: String,
        json: String? = null,
        csrf: String? = cookies[CSRF_COOKIE],
    ): MvcTestResult = exchange(HttpMethod.POST, path, json, csrf)

    fun put(
        path: String,
        json: String,
    ): MvcTestResult = exchange(HttpMethod.PUT, path, json)

    fun delete(
        path: String,
        headers: Map<String, String> = emptyMap(),
        csrf: String? = cookies[CSRF_COOKIE],
    ): MvcTestResult = exchange(HttpMethod.DELETE, path, csrf = csrf, headers = headers)

    fun exchange(
        method: HttpMethod,
        path: String,
        json: String? = null,
        csrf: String? = cookies[CSRF_COOKIE],
        headers: Map<String, String> = emptyMap(),
    ): MvcTestResult {
        val request =
            mvc
                .method(method)
                .uri(path)
                .secure(https)
                .with { it.apply { remoteAddr = address } }
        cookies.forEach { (name, value) -> request.cookie(Cookie(name, value)) }
        headers.forEach { (name, value) -> request.header(name, value) }
        if (csrf != null) request.header(CSRF_HEADER, csrf)
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json)
        val result = request.exchange()
        lastSetCookies = result.response.getHeaders("Set-Cookie")
        lastSetCookies.forEach(::remember)
        return result
    }

    /** Fetches a CSRF cookie the way the SPA does on start. */
    fun open(): Browser = also { get("/api/auth/session") }

    private fun remember(setCookie: String) {
        val (name, value) = setCookie.substringBefore(';').split("=", limit = 2)
        val expired = setCookie.contains("Max-Age=0", ignoreCase = true) || value.isEmpty()
        if (expired) cookies.remove(name) else cookies[name] = value
    }

    companion object {
        const val SESSION_COOKIE = "SESSION"
        const val CSRF_COOKIE = "XSRF-TOKEN"
        const val CSRF_HEADER = "X-XSRF-TOKEN"
    }
}
