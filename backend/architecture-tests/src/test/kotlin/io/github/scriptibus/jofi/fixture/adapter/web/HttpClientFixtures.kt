// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.fixture.adapter.web

import java.awt.image.BufferedImage
import java.net.DatagramSocket
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.net.http.HttpClient
import javax.imageio.ImageIO
import javax.net.ssl.SSLSocket

// Known-bad fixtures for `onlyTheNetAdapterMakesOutboundHttpCalls`: each one reaches the network
// outside adapters/net in a different way. Test fixtures only, never production.

/** Builds its own HTTP client. */
class JdkHttpClientInWebAdapterFixture {
    fun client(): HttpClient = HttpClient.newHttpClient()
}

/** Reads a URL with Kotlin's `readText()`. */
class UrlReadInWebAdapterFixture {
    fun read(uri: URI): String = uri.toURL().readText()
}

/** Merely holds a `URL` (URLs open connections and resolve DNS in `equals`; use `URI`). */
class UrlHolderInWebAdapterFixture(
    val link: URL,
)

/** Loads an image straight from a URL. */
class ImageIoInWebAdapterFixture {
    fun load(uri: URI): BufferedImage? = ImageIO.read(uri.toURL())
}

/** Loads classes over the network. */
class UrlClassLoaderInWebAdapterFixture {
    fun loader(uri: URI): ClassLoader = URLClassLoader.newInstance(arrayOf(uri.toURL()))
}

/** Raw TLS and UDP sockets. */
class SocketsInWebAdapterFixture(
    val tls: SSLSocket,
    val udp: DatagramSocket,
)
