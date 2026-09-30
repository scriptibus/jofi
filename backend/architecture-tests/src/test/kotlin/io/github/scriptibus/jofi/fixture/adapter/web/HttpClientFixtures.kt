// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import java.net.URI
import java.net.http.HttpClient

/** Known-bad: an adapter outside `adapters/net` building its own HTTP client. Test fixture only. */
class JdkHttpClientInWebAdapterFixture {
    fun client(): HttpClient = HttpClient.newHttpClient()
}

/** Known-bad: an adapter reading a URL directly, bypassing the SSRF guard. Test fixture only. */
class UrlReadInWebAdapterFixture {
    fun read(uri: URI): String = uri.toURL().readText()
}
