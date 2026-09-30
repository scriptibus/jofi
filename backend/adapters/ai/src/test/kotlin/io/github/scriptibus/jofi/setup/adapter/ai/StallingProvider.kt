// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A provider on a raw loopback socket for what WireMock cannot show: whether the client hung up.
 * It streams [events] (after [headerDelay]) and then goes silent, like a provider that stalls, and
 * counts every connection the client closes. With [answer] set it answers with that JSON instead.
 */
class StallingProvider(
    private val events: String,
    private val headerDelay: Duration = Duration.ZERO,
) : AutoCloseable {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val requests = AtomicInteger()
    val hangUps = AtomicInteger()

    @Volatile var answer: String? = null

    init {
        Thread.ofVirtual().start {
            while (!socket.isClosed) {
                val connection =
                    try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                Thread.ofVirtual().start { connection.use(::serve) }
            }
        }
    }

    private fun serve(connection: Socket) {
        try {
            readRequest(connection.getInputStream())
            requests.incrementAndGet()
            answer?.let { json ->
                respond(connection, "application/json", json, "Content-Length: ${json.toByteArray().size}\r\n")
                return
            }
            Thread.sleep(headerDelay)
            respond(connection, "text/event-stream", events, "Connection: close\r\n")
            while (connection.getInputStream().read() != -1) {
                // Silent: wait for the client to hang up.
            }
            hangUps.incrementAndGet()
        } catch (_: IOException) {
            hangUps.incrementAndGet()
        }
    }

    private fun respond(
        connection: Socket,
        contentType: String,
        body: String,
        framing: String,
    ) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n$framing\r\n"
        connection.getOutputStream().apply {
            write((head + body).toByteArray())
            flush()
        }
    }

    /** Reads the request head and, by its `Content-Length`, the body. */
    private fun readRequest(input: InputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) throw IOException("Client closed before the request ended")
            head.append(next.toChar())
        }
        val length =
            LENGTH
                .find(head)
                ?.groupValues
                ?.get(1)
                ?.toInt() ?: 0
        input.readNBytes(length)
    }

    override fun close() = socket.close()

    private companion object {
        const val BACKLOG = 50
        val LENGTH = Regex("(?i)content-length: *(\\d+)")
    }
}
